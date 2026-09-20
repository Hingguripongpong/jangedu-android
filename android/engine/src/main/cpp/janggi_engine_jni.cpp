// JNI surface for com.example.janggiai.engine.fsf.FairyStockfishNative.
//
// All methods are static on the Kotlin side, so no object references are retained here.
// Strings cross the boundary as modified UTF-8 (UCI is ASCII).  The package name is part
// of the symbol names below: if the app id is changed, keep the Kotlin class where it is
// or rename these functions together with it (see android/README.md "Changing the package").
#include <jni.h>

#include <string>

#include "engine_host.h"

namespace {

std::string toStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_janggiai_engine_fsf_FairyStockfishNative_nativeStart(JNIEnv*, jclass) {
    return janggi::EngineHost::instance().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_janggiai_engine_fsf_FairyStockfishNative_nativeSend(JNIEnv* env, jclass, jstring line) {
    janggi::EngineHost::instance().send(toStd(env, line));
}

// Returns the next output line, or null on timeout / when the engine is not running
// (use nativeIsRunning to tell the two apart).
JNIEXPORT jstring JNICALL
Java_com_example_janggiai_engine_fsf_FairyStockfishNative_nativeReadLine(JNIEnv* env, jclass, jint timeoutMs) {
    std::string line;
    int rc = janggi::EngineHost::instance().readLine(line, timeoutMs);
    if (rc != 1) return nullptr;
    return env->NewStringUTF(line.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_example_janggiai_engine_fsf_FairyStockfishNative_nativeIsRunning(JNIEnv*, jclass) {
    return janggi::EngineHost::instance().isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_janggiai_engine_fsf_FairyStockfishNative_nativeShutdown(JNIEnv*, jclass, jint timeoutMs) {
    return janggi::EngineHost::instance().shutdown(timeoutMs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_janggiai_engine_fsf_FairyStockfishNative_nativeEngineInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(janggi::EngineHost::instance().engineInfo().c_str());
}

}  // extern "C"
