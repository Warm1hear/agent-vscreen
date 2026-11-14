// tsbridge: the built-in Tailscale node of Agent-VScreen (1.1.6; 1.1.7 login link fix; 1.1.8 own control servers).
//
// It joins the user's tailnet as its own device (userspace tsnet: no VPN slot, no root, no Tailscale app) and forwards
// HTTP that tailnet peers send to <node>:PORT on to the app's local server at 127.0.0.1:PORT. Every forwarded request
// carries X-VScreen-Tailnet: <secret> (a random value the app gives us at start, so nobody on the phone can fake a
// tailnet request) and X-VScreen-Peer: <who> (the peer's Tailscale login and device, from WhoIs).
//
// 1.3.0: it runs INSIDE the app's process, as a shared library (libtsnode.so, see jni.go / jni.c), no longer as a
// child process. A child process is not an app process to Android: it has no foreground service of its own, and
// MagicOS froze it for about two minutes at a time while the app (protected by its foreground service) kept running,
// so the phone was unreachable whenever the user left the app. A child could also outlive an app process that was
// killed and keep the node's key on the relay. In-process, the node runs exactly when the app runs.
//
// The app talks to it through three calls: start(config JSON), command("login" | "logout" | "quit") and next(), which
// hands out one line at a time: "S{...}" a state (JSON), "L text" a log line, "H" a heartbeat every 2 s, "X code" the
// node has ended.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"tailscale.com/ipn"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

type status struct {
	State    string `json:"state"`
	AuthURL  string `json:"auth_url,omitempty"`
	IP       string `json:"ip,omitempty"`
	DNSName  string `json:"dns_name,omitempty"`
	Tailnet  string `json:"tailnet,omitempty"`
	User     string `json:"user,omitempty"`
	Peers    int    `json:"peers"`
	Online   int    `json:"online"`
	Error    string `json:"error,omitempty"`
	LastPeer string `json:"last_peer,omitempty"`
	// 1.1.7: why a login link is not there yet (control server unreachable, etc.), and how long we have waited.
	Health   string `json:"health,omitempty"`
	WaitingS int    `json:"waiting_s,omitempty"`
	// 1.1.8: the control server this node talks to ("" = Tailscale's own), and the last login error the backend
	// reported (unreachable server, bad address, rejected key ...), so the app can name the real cause.
	Control    string `json:"control,omitempty"`
	LoginError string `json:"login_error,omitempty"`
	// 1.3 beta4: when this node's login (node key) expires, unix seconds (0 = never or unknown), and how old the
	// login link shown to the user is.
	KeyExpiry int64 `json:"key_expiry,omitempty"`
	// 1.3 beta6: "Running" only says the node is logged in. Whether it can actually be reached is what control and
	// the home relay say: ControlOK = the coordination server is in touch; RelayOK = the home DERP is connected;
	// Relay = its region code; Flaps = how often either went down in the last 10 minutes; Problem = the health text.
	ControlOK bool   `json:"control_ok"`
	RelayOK   bool   `json:"relay_ok"`
	Relay     string `json:"relay,omitempty"`
	Flaps     int    `json:"flaps,omitempty"`
	Problem   string `json:"problem,omitempty"`
	DownS     int    `json:"down_s,omitempty"`
	LinkAgeS  int    `json:"link_age_s,omitempty"`
}

// normalizeControlURL turns what a person types ("hs.example.com", "https://hs.example.com/") into the URL tsnet needs.
// Without a scheme tsnet fails with "unsupported protocol scheme" and just sits in NeedsLogin.
func normalizeControlURL(v string) string {
	v = strings.TrimRight(strings.TrimSpace(v), "/")
	if v == "" {
		return ""
	}
	if !strings.Contains(v, "://") {
		v = "https://" + v
	}
	return v
}

// stopStale ends every bridge process an earlier app version left behind (1.1.6–1.3.0-beta8 ran the node as a child
// process named libtsbridge.so; one that outlived its app keeps the node's key on the relay and the state lock).
// The pid file catches beta8's; the scan of /proc catches older ones, which wrote none. SIGTERM first (it closes
// the node cleanly), SIGKILL after 3 s.
func stopStale(pidFile string) {
	var pids []int
	if b, err := os.ReadFile(pidFile); err == nil {
		if pid, err := strconv.Atoi(strings.TrimSpace(string(b))); err == nil {
			pids = append(pids, pid)
		}
	}
	if ents, err := os.ReadDir("/proc"); err == nil {
		for _, e := range ents {
			if pid, err := strconv.Atoi(e.Name()); err == nil {
				pids = append(pids, pid)
			}
		}
	}
	seen := map[int]bool{}
	for _, pid := range pids {
		if seen[pid] || pid <= 1 || pid == os.Getpid() {
			continue
		}
		seen[pid] = true
		cmd, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
		if err != nil || !strings.Contains(string(cmd), "tsbridge") {
			continue
		}
		logf("ending the bridge process left behind by an earlier app version (pid %d)", pid)
		_ = syscall.Kill(pid, syscall.SIGTERM)
		gone := false
		for i := 0; i < 30 && !gone; i++ {
			time.Sleep(100 * time.Millisecond)
			gone = syscall.Kill(pid, 0) != nil
		}
		if !gone {
			_ = syscall.Kill(pid, syscall.SIGKILL)
		}
	}
	_ = os.Remove(pidFile)
}

// hasSavedLogin: this node has logged in before (a profile is stored), whatever the backend says right now.
func hasSavedLogin(lc interface {
	ProfileStatus(context.Context) (ipn.LoginProfile, []ipn.LoginProfile, error)
}) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cur, _, err := lc.ProfileStatus(ctx)
	return err == nil && cur.ID != "" && cur.UserProfile.LoginName != ""
}

// controlUnreachable: the health notes say the control server could not be reached (not that it refused the login).
func controlUnreachable(health []string) bool {
	for _, h := range health {
		l := strings.ToLower(h)
		for _, w := range []string{"deadline exceeded", "fetch control key", "unable to connect", "coordination server", "timeout", "timed out", "connection refused", "no route", "network is unreachable", "dial tcp", "eof", "tls handshake"} {
			if strings.Contains(l, w) {
				return true
			}
		}
	}
	return false
}

// loginError pulls "The last login error was: X" out of the backend's health messages.
func loginError(health []string) string {
	for _, h := range health {
		if i := strings.Index(h, "last login error was: "); i >= 0 {
			return strings.TrimSpace(h[i+len("last login error was: "):])
		}
	}
	return ""
}

var (
	outMu    sync.Mutex
	last     string
	lastPeer string
	peerMu   sync.Mutex
)

func emit(s status) {
	peerMu.Lock()
	s.LastPeer = lastPeer
	peerMu.Unlock()
	b, _ := json.Marshal(s)
	outMu.Lock()
	defer outMu.Unlock()
	if string(b) == last {
		return
	}
	last = string(b)
	put("S" + string(b))
}

// 1.3: tsnet repeats some lines every few seconds (the login prompt, with its link) and they filled the app's
// diagnostics. Each distinct line is written at most once per 10 minutes, and login links are cut.
var (
	logMu   sync.Mutex
	logSeen = map[string]time.Time{}
	loginRe = regexp.MustCompile(`https?://\S*/a/\S+`)
)

// 1.3 beta6: the backend's own log is mostly noise, but the lines about links, relays and the control connection are
// what a flapping node needs explained. Those pass (at most 30 a minute), with the time.
var backendKeep = []string{"LinkChange", "home is now", "derphttp", "control: mapRoutine", "control: controlhttp", "netmon", "Rebind", "health(", "magicsock: derp-", "network map", "portmap"}
var backendMu sync.Mutex

var backendMin int64
var backendN int

func backendLog(f string, a ...any) {
	line := fmt.Sprintf(f, a...)
	keep := false
	for _, k := range backendKeep {
		if strings.Contains(line, k) {
			keep = true
			break
		}
	}
	if !keep {
		return
	}
	backendMu.Lock()
	m := time.Now().Unix() / 60
	if m != backendMin {
		backendMin, backendN = m, 0
	}
	backendN++
	n := backendN
	backendMu.Unlock()
	if n > 30 {
		return
	}
	logln("ts: " + loginRe.ReplaceAllString(strings.TrimSpace(line), "<login link>"))
}

func userLog(f string, a ...any) {
	line := loginRe.ReplaceAllString(fmt.Sprintf(f, a...), "<login link>")
	logMu.Lock()
	defer logMu.Unlock()
	if t, ok := logSeen[line]; ok && time.Since(t) < 10*time.Minute {
		return
	}
	if len(logSeen) > 500 {
		logSeen = map[string]time.Time{}
	}
	logSeen[line] = time.Now()
	logln(line)
}

// 1.3: requests are bounded here, before they reach the app: a body over 1 MiB is refused from its declared length
// (before, about 9 MB were read first), and a request whose headers and body do not arrive within 30 s is dropped.
const maxBody = 1 << 20

func bounded(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ContentLength > maxBody {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.Header().Set("Connection", "close")
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			fmt.Fprintf(w, `{"ok":false,"code":"BODY_TOO_LARGE","error":"request body over %d bytes"}`, maxBody)
			return
		}
		if r.Body != nil && r.Body != http.NoBody && r.ContentLength != 0 {
			// The body must arrive within 30 s; once it has, the deadline is lifted (a command may run for minutes).
			rc := http.NewResponseController(w)
			_ = rc.SetReadDeadline(time.Now().Add(30 * time.Second))
			r.Body = &untilEOF{ReadCloser: http.MaxBytesReader(w, r.Body, maxBody), done: func() { _ = rc.SetReadDeadline(time.Time{}) }}
		}
		h.ServeHTTP(w, r)
	})
}

type untilEOF struct {
	io.ReadCloser
	done func()
	once sync.Once
}

func (u *untilEOF) Read(p []byte) (int, error) {
	n, err := u.ReadCloser.Read(p)
	if err == io.EOF {
		u.once.Do(u.done)
	}
	return n, err
}

func or(v, def string) string {
	if v = strings.TrimSpace(v); v != "" {
		return v
	}
	return def
}

// Android has no /etc/resolv.conf: Go's own resolver would ask 127.0.0.1:53 and fail. Use the resolvers the app read
// from the phone's active network, then public ones.
func setupDNS(dns []string) {
	var servers []string
	for _, s := range dns {
		if s = strings.TrimSpace(s); s != "" {
			servers = append(servers, net.JoinHostPort(strings.Trim(s, "[]"), "53"))
		}
	}
	servers = append(servers, "223.5.5.5:53", "1.1.1.1:53", "8.8.8.8:53")
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			var err error
			for _, s := range servers {
				var c net.Conn
				if c, err = d.DialContext(ctx, network, s); err == nil {
					return c, nil
				}
			}
			return nil, err
		},
	}
}

// Apps targeting Android 11+ may not list interfaces through netlink; net.Interfaces can fail there. Fall back to one
// synthetic interface carrying the address the kernel would use for the internet (a UDP "connect" sends nothing).
var ifaceOnce sync.Once

func setupInterfaces() { ifaceOnce.Do(setupInterfaces1) }

func setupInterfaces1() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := net.Interfaces()
		if err == nil && len(ifs) > 0 {
			out := make([]netmon.Interface, 0, len(ifs))
			for i := range ifs {
				out = append(out, netmon.Interface{Interface: &ifs[i]})
			}
			return out, nil
		}
		c, derr := net.Dial("udp4", "8.8.8.8:53")
		if derr != nil {
			return nil, errors.Join(err, derr)
		}
		ip := c.LocalAddr().(*net.UDPAddr).IP
		c.Close()
		iface := &net.Interface{Index: 1, MTU: 1500, Name: "wlan0", Flags: net.FlagUp | net.FlagRunning | net.FlagMulticast}
		return []netmon.Interface{{Interface: iface, AltAddrs: []net.Addr{&net.IPNet{IP: ip, Mask: net.CIDRMask(24, 32)}}}}, nil
	})
}

// run is one life of the node: from start until [quit] is closed or it fails. It returns the exit code.
func run(c config, quit <-chan struct{}) int {
	setupDNS(c.DNS)
	setupInterfaces()
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true") // no log upload to Tailscale
	if os.Getenv("HOME") == "" {
		os.Setenv("HOME", c.Dir)
	}
	outMu.Lock()
	last = ""
	outMu.Unlock()

	port := or(c.Port, "18765")
	target := or(c.Target, "127.0.0.1:"+port)
	secret := c.Secret
	if secret == "" {
		emit(status{State: "Error", Error: "secret missing"})
		return 2
	}
	srv := &tsnet.Server{
		Dir:        or(c.Dir, "./ts"),
		Hostname:   or(c.Hostname, "agent-vscreen"),
		AuthKey:    c.AuthKey,
		ControlURL: normalizeControlURL(c.ControlURL),
		Logf:       backendLog,
		UserLogf:   userLog,
	}
	defer srv.Close()

	// 1.3 beta6: one node per identity. Two nodes on one state directory (the same node key) take turns on the
	// control server and the relay, so each is online for a while and then gone.
	// 1.3 beta8: a bridge process left behind by an earlier app version is ended first.
	// 1.3.0: the node runs in the app's process, so the lock is per process life; it is released when this run ends.
	stopStale(filepath.Join(srv.Dir, ".bridge.pid"))
	if f, err := os.OpenFile(filepath.Join(srv.Dir, ".bridge.lock"), os.O_CREATE|os.O_RDWR, 0o600); err == nil {
		defer f.Close()
		deadline := time.Now().Add(20 * time.Second)
		for {
			if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err == nil {
				break
			}
			if time.Now().After(deadline) {
				emit(status{State: "Error", Error: "another built-in Tailscale node is still running on this phone"})
				return 3
			}
			select {
			case <-quit:
				return 0
			case <-time.After(500 * time.Millisecond):
			}
		}
		defer syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
	}

	emit(status{State: "Starting"})
	if err := srv.Start(); err != nil {
		emit(status{State: "Error", Error: err.Error()})
		return 1
	}
	lc, err := srv.LocalClient()
	if err != nil {
		emit(status{State: "Error", Error: err.Error()})
		return 1
	}

	ln, err := srv.Listen("tcp", ":"+port)
	if err != nil {
		emit(status{State: "Error", Error: "listen: " + err.Error()})
		return 1
	}
	tu, _ := url.Parse("http://" + target)
	rp := httputil.NewSingleHostReverseProxy(tu)
	rp.FlushInterval = -1
	base := rp.Director
	rp.Director = func(r *http.Request) {
		host := r.Host
		base(r)
		r.Host = host // the app checks that the caller named this node
		r.Header.Del("X-VScreen-Tailnet")
		r.Header.Del("X-VScreen-Peer")
		r.Header.Set("X-VScreen-Tailnet", secret)
		who := r.RemoteAddr
		if wi, err := lc.WhoIs(r.Context(), r.RemoteAddr); err == nil && wi != nil {
			name := ""
			if wi.UserProfile != nil {
				name = wi.UserProfile.LoginName
			}
			if wi.Node != nil {
				name += " @ " + strings.TrimSuffix(wi.Node.ComputedName, ".")
			}
			if name != "" {
				who = name
			}
		}
		r.Header.Set("X-VScreen-Peer", who)
		peerMu.Lock()
		lastPeer = who
		peerMu.Unlock()
	}
	rp.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusBadGateway)
		fmt.Fprintf(w, `{"ok":false,"code":"APP_NOT_ANSWERING","error":"Agent-VScreen on the phone did not answer (%s): open the app once / 手机上的 Agent-VScreen 没有回应：打开一次 App"}`, strings.ReplaceAll(err.Error(), `"`, `'`))
	}
	go func() {
		_ = (&http.Server{Handler: bounded(rp), ReadHeaderTimeout: 30 * time.Second}).Serve(ln)
	}()

	// 1.1.7: tsnet asks the control server for a login link only once, and only if the backend is already in NeedsLogin
	// at that instant. When it is still NoState then (slow storage, an old state file, an expired node) or after a
	// logout or an expired link, the node sits in NeedsLogin with no link forever. So we ask ourselves: whenever the
	// state is NeedsLogin without a link, at most every 20 s, and right away on a "login" command from the app.
	var loginMu sync.Mutex
	var lastKick time.Time
	kick := func(force bool) {
		loginMu.Lock()
		if !force && time.Since(lastKick) < 20*time.Second {
			loginMu.Unlock()
			return
		}
		lastKick = time.Now()
		loginMu.Unlock()
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			if err := lc.StartLoginInteractive(ctx); err != nil {
				logln("StartLoginInteractive: " + err.Error())
			}
		}()
	}
	// Commands from the app (login, logout); quit closes [quit].
	go func() {
		for {
			select {
			case <-quit:
				return
			case cmd := <-commands:
				switch cmd {
				case "logout":
					ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
					_ = lc.Logout(ctx)
					cancel()
				case "login":
					kick(true)
				}
			}
		}
	}()

	var needSince time.Time
	// 1.3 beta6: the real link state and its changes.
	var wasUp = true
	var downSince time.Time
	var flaps []time.Time
	// 1.3 beta4: a login link is not good forever. One left open for 10 minutes (the user went away, the browser tab
	// was lost) is replaced with a fresh one, so the button in the app never opens a dead page.
	var link string
	var linkSince time.Time
	var keptLogin bool
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		st, err := lc.Status(ctx)
		cancel()
		if err != nil {
			emit(status{State: "Error", Error: err.Error()})
		} else {
			s := status{State: st.BackendState, AuthURL: st.AuthURL, Control: srv.ControlURL}
			if st.BackendState != "Running" {
				s.LoginError = loginError(st.Health)
			}
			if st.BackendState == "NeedsLogin" && st.AuthURL == "" {
				if needSince.IsZero() {
					needSince = time.Now()
				}
				s.WaitingS = int(time.Since(needSince) / time.Second)
				s.Health = strings.Join(st.Health, "; ")
				// 1.3 beta7: a node that has a saved login and lost the control server (slow or blocked network, a
				// proxy, the first seconds after a start) also reports NeedsLogin without a link. Starting an
				// interactive login then threw the saved login away, and the user was asked to log in again although
				// nothing had expired. Keep it: say "reconnecting" and wait for the server; ask for a new login only
				// when the server is answering and the node still wants one after a minute.
				saved := hasSavedLogin(lc)
				if saved && (controlUnreachable(st.Health) || time.Since(needSince) < time.Minute) {
					s.State = "Reconnecting"
					s.Problem = strings.Join(st.Health, "; ")
					if !keptLogin {
						keptLogin = true
						logf("needs login but has a saved login: keeping it, waiting for the server (%s)", s.Problem)
					}
				} else if srv.AuthKey == "" {
					if saved {
						logf("saved login refused by the server after %ds: asking for a new login", s.WaitingS)
					}
					kick(false)
				}
			} else {
				needSince = time.Time{}
				keptLogin = false
			}
			if st.BackendState == "NeedsLogin" && st.AuthURL != "" {
				if st.AuthURL != link {
					link, linkSince = st.AuthURL, time.Now()
				}
				s.LinkAgeS = int(time.Since(linkSince) / time.Second)
				if time.Since(linkSince) > 10*time.Minute && srv.AuthKey == "" {
					kick(true)
					linkSince = time.Now()
				}
			} else {
				link = ""
			}
			if len(st.TailscaleIPs) > 0 {
				for _, ip := range st.TailscaleIPs {
					if ip.Is4() {
						s.IP = ip.String()
					}
				}
			}
			if st.Self != nil {
				s.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
				if st.Self.KeyExpiry != nil && !st.Self.KeyExpiry.IsZero() {
					s.KeyExpiry = st.Self.KeyExpiry.Unix()
				}
				if u, ok := st.User[st.Self.UserID]; ok {
					s.User = u.LoginName
				}
			}
			if st.CurrentTailnet != nil {
				s.Tailnet = st.CurrentTailnet.Name
			}
			if st.BackendState == "Running" {
				s.ControlOK, s.RelayOK = true, true
				var probs []string
				for _, h := range st.Health {
					l := strings.ToLower(h)
					switch {
					case strings.Contains(l, "coordination server"):
						s.ControlOK = false
						probs = append(probs, h)
					case strings.Contains(l, "relay server"):
						s.RelayOK = false
						probs = append(probs, h)
					}
				}
				if st.Self != nil {
					s.Relay = st.Self.Relay
					if s.Relay == "" {
						s.RelayOK = false
					}
				}
				s.Problem = strings.Join(probs, "; ")
				up := s.ControlOK && s.RelayOK
				if up != wasUp {
					if !up {
						downSince = time.Now()
						flaps = append(flaps, time.Now())
						logf("link down: control_ok=%v relay=%q relay_ok=%v %s", s.ControlOK, s.Relay, s.RelayOK, s.Problem)
					} else {
						logf("link up again after %ds, relay=%q", int(time.Since(downSince)/time.Second), s.Relay)
					}
					wasUp = up
				}
				if !up {
					s.DownS = int(time.Since(downSince) / time.Second)
				}
				cut := time.Now().Add(-10 * time.Minute)
				for len(flaps) > 0 && flaps[0].Before(cut) {
					flaps = flaps[1:]
				}
				s.Flaps = len(flaps)
			}
			for _, p := range st.Peer {
				s.Peers++
				if p.Online {
					s.Online++
				}
			}
			emit(s)
		}
		put("H")
		select {
		case <-quit:
			return 0
		case <-tick.C:
		}
	}
}
