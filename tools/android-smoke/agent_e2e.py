"""End-to-end check on an emulator: an agent drives the background screen through the real HTTP API.

Usage (inside android-emulator-runner): python3 tools/android-smoke/agent_e2e.py test-apks/app-debug.apk
Needs test-apks/shizuku.apk. Each check prints PASS/FAIL; the script exits non-zero if any check failed.
What it guards: the service comes up with Shizuku, `launch` builds a screen and opens an app there, the user's main
screen is not touched, `look` and `act` work on it, `release` closes it, and the app process never crashes or loses a
background thread on the way (status background_thread_deaths).
"""
import json
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "com.kilon.agentvscreen"
MANAGER = "moe.shizuku.privileged.api"
APK = Path(sys.argv[1] if len(sys.argv) > 1 else "test-apks/app-debug.apk")
SHIZUKU = APK.parent / "shizuku.apk"
FAILED = []


def run(*argv, timeout=90):
    return subprocess.check_output(argv, text=True, timeout=timeout, stderr=subprocess.STDOUT).strip()


def shell(*args, timeout=90):
    return run("adb", "shell", *args, timeout=timeout)


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (" :: " + detail if detail else ""), flush=True)
    if not ok:
        FAILED.append(name)
    return ok


def shizuku_state():
    states = re.findall(r"state=(\w+)", shell("logcat", "-d", "-s", "ShizukuManager:I"))
    return states[-1] if states else None


def wait_state(expected, secs=60):
    for _ in range(secs):
        if shizuku_state() == expected:
            return True
        time.sleep(1)
    return False


def press_text(package, labels, tries=20):
    want = {x.casefold() for x in labels}
    seen = set()
    for _ in range(tries):
        if "dumped" in shell("uiautomator", "dump", "/data/local/tmp/w.xml").lower():
            for node in ET.fromstring(shell("cat", "/data/local/tmp/w.xml")).iter("node"):
                a = node.attrib
                label = (a.get("text") or a.get("content-desc", "")).strip()
                if label:
                    seen.add(a.get("package", "?") + ":" + label)
                if a.get("package") == package and label.casefold() in want:
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", a["bounds"]))
                    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    print("tapped", package, label, flush=True)
                    return True
        time.sleep(1)
    print("no", labels, "in", package, "; on screen:", sorted(seen)[:30], flush=True)
    return False


def setup():
    print("SDK", shell("getprop", "ro.build.version.sdk"), "ABIs", shell("getprop", "ro.product.cpu.abilist"), flush=True)
    run("adb", "install", "-r", str(SHIZUKU))
    shell("monkey", "-p", MANAGER, "1")
    pkgdir = Path(shell("pm", "path", MANAGER).removeprefix("package:")).parent
    shell(shell("find", str(pkgdir / "lib"), "-name", "libshizuku.so").splitlines()[0])
    shell("input", "keyevent", "KEYCODE_HOME")
    shell("logcat", "-c")
    run("adb", "install", "-r", "-g", str(APK), timeout=240)
    # The element list comes from the app's accessibility service, which a user turns on in setup; do the same here.
    shell("settings", "put", "secure", "enabled_accessibility_services", APP + "/.host.VScreenAccessibilityService")
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    shell("am", "start", "-W", "-n", APP + "/.ui.MainActivity", "--es", "page", "SETUP")
    if not wait_state("NEED_PERMISSION", 45) and shizuku_state() != "READY":
        raise SystemExit("FAIL app never saw Shizuku: " + str(shizuku_state()))
    for _ in range(3):
        if shizuku_state() == "READY":
            break
        press_text(APP, ["Grant Shizuku", "授权 Shizuku", "Grant", "授权"])
        press_text(MANAGER, ["Allow all the time", "始终允许", "Allow", "允许"])
        wait_state("READY", 10)
    check("shizuku granted", wait_state("READY", 45), str(shizuku_state()))
    shell("input", "keyevent", "KEYCODE_HOME")


def seed():
    shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP)
    out = ""
    for _ in range(90):
        out = shell("run-as", APP, "cat", "files/ci.txt", "2>/dev/null", "||", "true")
        parts = out.split()
        if len(parts) == 2 and parts[1] != "0":
            run("adb", "forward", "tcp:18766", "tcp:" + parts[1])
            return parts[0]
        time.sleep(1)
    print("ci.txt:", repr(out[:8] + "..." if out else out))
    print(shell("dumpsys", "activity", "services", APP)[-1500:])
    print(shell("logcat", "-d", "-t", "200", "--pid=" + (pid() or "0"))[-4000:])
    raise SystemExit("FAIL service never reported its port")


TOKEN = None


def api(cmd, session="ci", timeout=180):
    try:
        return _api(cmd, session, timeout)
    except Exception as e:  # the step fails, the run goes on, and the app's own log says why
        print("call %r failed: %r" % (cmd, e), flush=True)
        print("pid now:", pid() or "(gone)", flush=True)
        try:
            print(shell("logcat", "-d", "-t", "300", "-v", "brief", "AndroidRuntime:E", "Crash:E", "VScreenRemoteDisplay:*", "BackgroundOperation:*", "ApiServer:*", "*:S")[-6000:], flush=True)
        except Exception as e2:
            print("logcat failed:", e2)
        return {"ok": False, "code": "CALL_FAILED:" + type(e).__name__}


def _api(cmd, session, timeout):
    body = json.dumps({"cmd": cmd, "session": session}).encode()
    req = urllib.request.Request("http://127.0.0.1:18766/v2/run", data=body, method="POST",
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json", "X-VScreen-Lang": "en"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def pid():
    try:
        return shell("pidof", APP)
    except subprocess.CalledProcessError:
        return ""


def main_display_top():
    """Package of the resumed activity on display 0 (the user's screen), or None if it cannot be read."""
    out = shell("dumpsys", "activity", "activities")
    block = out.split("Display #0", 1)[-1].split("Display #", 1)[0] if "Display #0" in out else ""
    m = re.search(r"(?:topResumedActivity|mResumedActivity|ResumedActivity)[:=]\s*ActivityRecord\{\S+ \S+ ([\w.]+)/", block)
    return m.group(1) if m else None


def main():
    global TOKEN
    setup()
    TOKEN = seed()
    with urllib.request.urlopen("http://127.0.0.1:18766/v2/ping", timeout=10) as r:
        check("ping", r.status == 200)
    p0 = pid()
    before = main_display_top()

    st = api("status")
    check("status ok", st.get("ok") is True, st.get("code", ""))

    r = api("launch com.android.settings", timeout=180)
    app = (r.get("screen") or {}).get("app")
    # Element lists come back with every launch/act reply; look is only for pictures.
    els = (r.get("screen") or {}).get("elements") or []
    check("launch builds a screen and opens Settings there", r.get("ok") is True and app == "com.android.settings",
          "%s app=%s" % (r.get("code"), app))
    after = main_display_top()
    check("main screen untouched by launch", after is None or after != "com.android.settings" or before == "com.android.settings",
          "before=%s after=%s" % (before, after))

    check("launch reply lists elements", len(els) > 0, "elements=%d" % len(els))
    if not els:
        print(str(r.get("text"))[:3000], flush=True)
    r = api("look")
    check("look returns a picture", r.get("ok") is True and len(r.get("images") or []) > 0,
          "%s images=%d" % (r.get("code"), len(r.get("images") or [])))

    target = next((e.get("text") for e in els if e.get("tappable") and e.get("text")), None)
    if target:
        r = api('act "tap %s; key back"' % target.replace('"', ""))
        check("act tap + back", r.get("ok") is True or r.get("code") in ("NO_CHANGE",), "%s on %r" % (r.get("code"), target))
    else:
        check("act target found", False, "no clickable text in look")

    # A second session must be told the screen is busy, not get a second screen.
    r = api("launch com.android.settings", session="other")
    check("second session told BUSY", r.get("code") == "BUSY", str(r.get("code")))

    r = api("release")
    check("release", r.get("ok") is True, str(r.get("code")))

    st = api("status")
    text = json.dumps(st)
    m = re.search(r'"background_thread_deaths":\s*(\d+)', text)
    check("no background thread died", m is None or m.group(1) == "0", m.group(0) if m else "not in status")
    check("app process survived the run", p0 != "" and pid() == p0, "pid %s -> %s" % (p0, pid()))
    crashes = shell("logcat", "-d", "-b", "crash")
    check("no crash in logcat", APP not in crashes, crashes[-600:])

    print("SUMMARY", "all passed" if not FAILED else "failed: " + ", ".join(FAILED), flush=True)
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
