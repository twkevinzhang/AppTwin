#include <elf.h>//
// VirtualApp Native Project
//
#include <Foundation/IOUniformer.h>
#include <fb/include/fb/Build.h>
#include <fb/include/fb/ALog.h>
#include <fb/include/fb/fbjni.h>
#include <ctime>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include "VAJni.h"

using namespace facebook::jni;

// Sanitized kernel TCP state only; no addresses, tokens or packet data cross Binder.
static jint jni_nativeTcpState(alias_ref<jclass>, jobject descriptor) {
    if (!descriptor) return 0;
    JNIEnv* env = Environment::current();
    jclass cls = env->GetObjectClass(descriptor);
    jfieldID field = env->GetFieldID(cls, "descriptor", "I");
    env->DeleteLocalRef(cls);
    if (!field) { env->ExceptionClear(); return 0; }
    int fd = env->GetIntField(descriptor, field);
    struct tcp_info info = {};
    socklen_t size = sizeof(info);
    if (getsockopt(fd, IPPROTO_TCP, TCP_INFO, &info, &size) != 0) return 0;
    return info.tcpi_state == TCP_ESTABLISHED ? 1 : 2;
}

static void jni_nativeLaunchEngine(alias_ref<jclass> clazz, JArrayClass<jobject> javaMethods,
                                   jstring packageName,
                                   jboolean isArt, jint apiLevel, jint cameraMethodType) {
    hookAndroidVM(javaMethods, packageName, isArt, apiLevel, cameraMethodType);
}

static void jni_disableJit(alias_ref<jclass> clazz, jint apiLevel) {
    disableJit(apiLevel);
}

static jboolean jni_nativeEnableIORedirect(alias_ref<jclass>, jstring selfSoPath, jstring hostPackage,
                                       jstring guestProcessName, jint apiLevel,
                                       jint preview_api_level) {
    ScopeUtfString so_path(selfSoPath);
    ScopeUtfString host_package(hostPackage);
    ScopeUtfString guest_process_name(guestProcessName);
    ALOGI("SharedMediaHooks phase=jni-start api=%d", apiLevel);
    IOUniformer::startUniformer(so_path.c_str(), host_package.c_str(),
                                guest_process_name.c_str(), apiLevel, preview_api_level);
    ALOGI("SharedMediaHooks phase=jni-uniformer-return");
    bool ready = IOUniformer::sharedMediaHooksReady();
    ALOGI("SharedMediaHooks phase=jni-complete ready=%d", ready);
    return ready;
}

static void jni_nativeConfigureUidOverride(alias_ref<jclass>, jint uidOverride) {
    IOUniformer::configureUidOverride(uidOverride);
}

static jint jni_nativeReadUidForProbe(alias_ref<jclass>) {
    return IOUniformer::readUidForProbe();
}

static jint jni_nativeCountProcMapsLeaksForProbe(alias_ref<jclass>) {
    return IOUniformer::countProcMapsLeaksForProbe();
}

static jboolean jni_nativeIOSharedMediaAccess(alias_ref<jclass>, jstring _path,
                                              jboolean read, jboolean write) {
    ScopeUtfString path(_path);
    return IOUniformer::sharedMediaAccess(path.c_str(), read, write);
}

static void jni_nativeIOWhitelist(alias_ref<jclass> jclazz, jstring _path) {
    ScopeUtfString path(_path);
    IOUniformer::whitelist(path.c_str());
}

static void jni_nativeIOForbid(alias_ref<jclass> jclazz, jstring _path) {
    ScopeUtfString path(_path);
    IOUniformer::forbid(path.c_str());
}


static void jni_nativeIORedirect(alias_ref<jclass> jclazz, jstring origPath, jstring newPath) {
    ScopeUtfString orig_path(origPath);
    ScopeUtfString new_path(newPath);
    IOUniformer::redirect(orig_path.c_str(), new_path.c_str());

}

static jstring jni_nativeGetRedirectedPath(alias_ref<jclass> jclazz, jstring origPath) {
    ScopeUtfString orig_path(origPath);
    const char *redirected_path = IOUniformer::query(orig_path.c_str());
    if (redirected_path != NULL) {
        jstring result = Environment::current()->NewStringUTF(redirected_path);
        if (redirected_path != orig_path.c_str()) {
            free((void *) redirected_path);
        }
        return result;
    }
    return NULL;
}

static jstring jni_nativeReverseRedirectedPath(alias_ref<jclass> jclazz, jstring redirectedPath) {
    ScopeUtfString redirected_path(redirectedPath);
    const char *orig_path = IOUniformer::reverse(redirected_path.c_str());
    jstring result = Environment::current()->NewStringUTF(orig_path);
    if (orig_path != redirected_path.c_str()) {
        free((void *) orig_path);
    }
    return result;
}


alias_ref<jclass> nativeEngineClass;

const char hexcode[] = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E',
                        'F'};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv* env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    return initialize(vm, [] {
        nativeEngineClass = findClassStatic("com/lody/virtual/client/NativeEngine");
        nativeEngineClass->registerNatives({
                        makeNativeMethod("nativeReadTcpState", jni_nativeTcpState),
                        makeNativeMethod("nativeEnableIORedirect",
                                         jni_nativeEnableIORedirect),
                        makeNativeMethod("nativeConfigureUidOverride",
                                         jni_nativeConfigureUidOverride),
                        makeNativeMethod("nativeReadUidForProbe",
                                         jni_nativeReadUidForProbe),
                        makeNativeMethod("nativeCountProcMapsLeaksForProbe",
                                         jni_nativeCountProcMapsLeaksForProbe),
                        makeNativeMethod("nativeIOSharedMediaAccess", jni_nativeIOSharedMediaAccess),
                        makeNativeMethod("nativeIOWhitelist",
                                         jni_nativeIOWhitelist),
                        makeNativeMethod("nativeIOForbid",
                                         jni_nativeIOForbid),
                        makeNativeMethod("nativeIORedirect",
                                         jni_nativeIORedirect),
                        makeNativeMethod("nativeGetRedirectedPath",
                                         jni_nativeGetRedirectedPath),
                        makeNativeMethod("nativeReverseRedirectedPath",
                                         jni_nativeReverseRedirectedPath),
                        makeNativeMethod("nativeLaunchEngine",
                                         jni_nativeLaunchEngine),
                        makeNativeMethod("disableJit", jni_disableJit)
                }
        );
    });
}

extern "C" __attribute__((constructor)) void _init(void) {
    IOUniformer::init_env_before_all();
}
