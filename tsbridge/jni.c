// JNI surface of the in-process Tailscale node (1.3.0): com.kilon.agentvscreen.host.TsNative.
#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

static char *dup_utf(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (c == NULL) return NULL;
    jsize n = (*env)->GetStringUTFLength(env, s);
    char *out = malloc((size_t) n + 1);
    if (out != NULL) { for (jsize i = 0; i < n; i++) out[i] = c[i]; out[n] = 0; }
    (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

JNIEXPORT jint JNICALL Java_com_kilon_agentvscreen_host_TsNative_start(JNIEnv *env, jclass cls, jbyteArray cfg) {
    jsize n = (*env)->GetArrayLength(env, cfg);
    char *c = malloc((size_t) n + 1);
    if (c == NULL) return 2;
    (*env)->GetByteArrayRegion(env, cfg, 0, n, (jbyte *) c);
    c[n] = 0;
    jint r = tsStart(c);
    free(c);
    return r;
}

JNIEXPORT void JNICALL Java_com_kilon_agentvscreen_host_TsNative_command(JNIEnv *env, jclass cls, jstring cmd) {
    char *c = dup_utf(env, cmd);
    if (c == NULL) return;
    tsCommand(c);
    free(c);
}

JNIEXPORT jboolean JNICALL Java_com_kilon_agentvscreen_host_TsNative_running(JNIEnv *env, jclass cls) {
    return tsRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_com_kilon_agentvscreen_host_TsNative_next(JNIEnv *env, jclass cls, jint ms) {
    int n = 0;
    char *l = tsNext(ms, &n);
    if (l == NULL) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) l);
    free(l);
    return out;
}
