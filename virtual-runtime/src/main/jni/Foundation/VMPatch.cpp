//
// VirtualApp Native Project
//
#include <Jni/VAJni.h>
#include <Substrate/CydiaSubstrate.h>
#include "VMPatch.h"
#include "fake_dlfcn.h"
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <link.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <A64Inlinehook/And64InlineHook.hpp>

namespace FunctionDef {
    typedef void (*Function_DalvikBridgeFunc)(const void **, void *, const void *, void *);

    typedef jobject (*Function_openDexNativeFunc)(JNIEnv *, jclass, jstring, jstring, jint);

    typedef jobject (*Native_openDexNativeFunc_N)(JNIEnv *, jclass, jstring, jstring, jint, jobject,
                                                  jobject);


    typedef jint (*Function_cameraNativeSetupFunc_T1)(JNIEnv *, jobject, jobject, jint, jstring);

    typedef jint (*Function_cameraNativeSetupFunc_T2)(JNIEnv *, jobject, jobject, jint, jint,
                                                      jstring);

    typedef jint (*Function_cameraNativeSetupFunc_T3)(JNIEnv *, jobject, jobject, jint, jint,
                                                      jstring,
                                                      jboolean);

    typedef jint (*Function_cameraNativeSetupFunc_T4)(JNIEnv *, jobject, jobject, jint, jstring,
                                                      jboolean);

    typedef jint (*Function_getCallingUid)(JNIEnv *, jclass);

    typedef jint (*Function_audioRecordNativeCheckPermission)(JNIEnv *, jobject, jstring);
}

using namespace FunctionDef;


static struct {

    bool is_art;
    int native_offset;
    char *host_packageName;
    jint api_level;
    jmethodID method_onGetCallingUid;
    jmethodID method_onOpenDexFileNative;

    void *art_work_around_app_jni_bugs;

    char *(*GetCstrFromString)(void *);

    void *(*GetStringFromCstr)(const char *);

    int (*native_getCallingUid)(int);

    int (*IPCThreadState_self)(void);

    Function_getCallingUid jni_orig_getCallingUid;
    Function_DalvikBridgeFunc orig_cameraNativeSetup_dvm;

    int cameraMethodType;
    union {
        Function_cameraNativeSetupFunc_T1 t1;
        Function_cameraNativeSetupFunc_T2 t2;
        Function_cameraNativeSetupFunc_T3 t3;
        Function_cameraNativeSetupFunc_T4 t4;
    } orig_native_cameraNativeSetupFunc;

    Function_DalvikBridgeFunc orig_openDexFile_dvm;
    union {
        Function_openDexNativeFunc beforeN;
        Native_openDexNativeFunc_N afterN;
    } orig_openDexNativeFunc_art;

    Function_audioRecordNativeCheckPermission orig_audioRecordNativeCheckPermission;

} patchEnv;


jint getCallingUid(alias_ref<jclass> clazz) {
    jint uid;
    if (patchEnv.is_art) {
        uid = patchEnv.jni_orig_getCallingUid(Environment::ensureCurrentThreadIsAttached(),
                                              clazz.get());
    } else {
        uid = patchEnv.native_getCallingUid(patchEnv.IPCThreadState_self());
    }
    uid = Environment::ensureCurrentThreadIsAttached()->CallStaticIntMethod(nativeEngineClass.get(),
                                                                            patchEnv.method_onGetCallingUid,
                                                                            uid);
    return uid;
}


static jobject new_native_openDexNativeFunc(JNIEnv *env, jclass jclazz, jstring javaSourceName,
                                            jstring javaOutputName, jint options) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(2, stringClass, NULL);

    if (javaSourceName) {
        env->SetObjectArrayElement(array, 0, javaSourceName);
    }
    if (javaOutputName) {
        env->SetObjectArrayElement(array, 1, javaOutputName);
    }
    env->CallStaticVoidMethod(nativeEngineClass.get(), patchEnv.method_onOpenDexFileNative, array);

    jstring newSource = (jstring) env->GetObjectArrayElement(array, 0);
    jstring newOutput = (jstring) env->GetObjectArrayElement(array, 1);

    return patchEnv.orig_openDexNativeFunc_art.beforeN(env, jclazz, newSource, newOutput,
                                                       options);
}

static jobject new_native_openDexNativeFunc_N(JNIEnv *env, jclass jclazz, jstring javaSourceName,
                                              jstring javaOutputName, jint options, jobject loader,
                                              jobject elements) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(2, stringClass, NULL);

    if (javaSourceName) {
        env->SetObjectArrayElement(array, 0, javaSourceName);
    }
    if (javaOutputName) {
        env->SetObjectArrayElement(array, 1, javaOutputName);
    }
    env->CallStaticVoidMethod(nativeEngineClass.get(), patchEnv.method_onOpenDexFileNative, array);

    jstring newSource = (jstring) env->GetObjectArrayElement(array, 0);
    jstring newOutput = (jstring) env->GetObjectArrayElement(array, 1);

    return patchEnv.orig_openDexNativeFunc_art.afterN(env, jclazz, newSource, newOutput, options,
                                                      loader, elements);
}


static void
new_bridge_openDexNativeFunc(const void **args, void *pResult, const void *method, void *self) {

    JNIEnv *env = Environment::ensureCurrentThreadIsAttached();

    const char *source = args[0] == NULL ? NULL : patchEnv.GetCstrFromString((void *) args[0]);
    const char *output = args[1] == NULL ? NULL : patchEnv.GetCstrFromString((void *) args[1]);

    jstring orgSource = source == NULL ? NULL : env->NewStringUTF(source);
    jstring orgOutput = output == NULL ? NULL : env->NewStringUTF(output);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(2, stringClass, NULL);
    if (orgSource) {
        env->SetObjectArrayElement(array, 0, orgSource);
    }
    if (orgOutput) {
        env->SetObjectArrayElement(array, 1, orgOutput);
    }
    env->CallStaticVoidMethod(nativeEngineClass.get(), patchEnv.method_onOpenDexFileNative, array);

    jstring newSource = (jstring) env->GetObjectArrayElement(array, 0);
    jstring newOutput = (jstring) env->GetObjectArrayElement(array, 1);

    const char *_newSource = newSource == NULL ? NULL : env->GetStringUTFChars(newSource, NULL);
    const char *_newOutput = newOutput == NULL ? NULL : env->GetStringUTFChars(newOutput, NULL);

    args[0] = _newSource == NULL ? NULL : patchEnv.GetStringFromCstr(_newSource);
    args[1] = _newOutput == NULL ? NULL : patchEnv.GetStringFromCstr(_newOutput);

    if (source && orgSource) {
        env->ReleaseStringUTFChars(orgSource, source);
    }
    if (output && orgOutput) {
        env->ReleaseStringUTFChars(orgOutput, output);
    }

    patchEnv.orig_openDexFile_dvm(args, pResult, method, self);
}

static bool guestPermissionGranted(JNIEnv *env, const char *permission) {
    jmethodID check = env->GetStaticMethodID(nativeEngineClass.get(), "onCheckGuestPermission",
                                             "(Ljava/lang/String;)Z");
    if (check == nullptr || env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    jstring value = env->NewStringUTF(permission);
    jboolean granted = env->CallStaticBooleanMethod(nativeEngineClass.get(), check, value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return granted == JNI_TRUE;
}

// Native Camera/AudioRecord bypass Java service proxies on modern Android. Gate the
// outbound Binder request before the host UID can exercise those capabilities.
static int (*originalBpTransact)(void *, uint32_t, const void *, void *, uint32_t);
static const uint8_t *(*parcelData)(const void *);
static size_t (*parcelSize)(const void *);
static bool parcelHasInterface(const void *parcel, const char *descriptor) {
    const uint8_t *data = parcelData(parcel);
    size_t size = parcelSize(parcel), length = strlen(descriptor);
    if (!data) return false;
    // StrictMode/work-source headers differ by release. Inspect only token positions.
    for (size_t offset = 0; offset <= 12; offset += 4) {
        if (offset + 4 + (length + 1) * 2 > size) continue;
        int32_t count;
        memcpy(&count, data + offset, 4);
        if (count != static_cast<int32_t>(length)) continue;
        bool equal = true;
        for (size_t i = 0; i < length; ++i) {
            if (data[offset + 4 + i * 2] != descriptor[i]
                    || data[offset + 5 + i * 2] != 0) { equal = false; break; }
        }
        if (equal) return true;
    }
    return false;
}
static int guardedBpTransact(void *binder, uint32_t code, const void *data,
                             void *reply, uint32_t flags) {
    const char *permission = nullptr;
    if (parcelHasInterface(data, "android.hardware.ICameraService")) {
        permission = "android.permission.CAMERA";
    } else if (code == 2 && (parcelHasInterface(data, "android.media.IAudioFlingerService")
            || parcelHasInterface(data, "android.media.IAudioFlinger"))) {
        // AOSP API31 IAudioFlingerService: createTrack=1, createRecord=2.
        permission = "android.permission.RECORD_AUDIO";
    }
    if (permission && !guestPermissionGranted(Environment::ensureCurrentThreadIsAttached(), permission)) {
        return -13; // PERMISSION_DENIED; no sensitive transaction reaches the platform.
    }
    return originalBpTransact(binder, code, data, reply, flags);
}
// App linker namespaces intentionally hide libbinder from dlopen. Resolve only an
// already-loaded module using its linker's load bias and its validated ELF dynsym.
// Unlike legacy fake_dlopen, this does not infer the base from an executable map.
struct LoadedBinderModule { uintptr_t base; char path[1024]; };
static int findLoadedBinder(struct dl_phdr_info *info, size_t, void *output) {
    if (!info->dlpi_name) return 0;
    const char *name = strrchr(info->dlpi_name, '/');
    name = name ? name + 1 : info->dlpi_name;
    if (strcmp(name, "libbinder.so") != 0) return 0;
    auto *module = static_cast<LoadedBinderModule *>(output);
    module->base = info->dlpi_addr;
    snprintf(module->path, sizeof(module->path), "%s", info->dlpi_name);
    return 1;
}
static void *loadedBinderSymbol(const LoadedBinderModule &module, const char *name) {
    if (!module.path[0]) return nullptr;
    int fd = open(module.path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return nullptr;
    struct stat metadata{};
    if (fstat(fd, &metadata) != 0 || metadata.st_size < sizeof(Elf64_Ehdr)) { close(fd); return nullptr; }
    size_t length = static_cast<size_t>(metadata.st_size);
    const auto *bytes = static_cast<const uint8_t *>(mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (bytes == MAP_FAILED) return nullptr;
    void *found = nullptr;
    const auto *header = reinterpret_cast<const Elf64_Ehdr *>(bytes);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) == 0
            && header->e_ident[EI_CLASS] == ELFCLASS64
            && header->e_shentsize == sizeof(Elf64_Shdr)
            && header->e_shoff <= length
            && static_cast<size_t>(header->e_shnum) * sizeof(Elf64_Shdr) <= length - header->e_shoff) {
        const auto *sections = reinterpret_cast<const Elf64_Shdr *>(bytes + header->e_shoff);
        for (size_t i = 0; i < header->e_shnum && !found; i++) {
            const auto &symbols = sections[i];
            if (symbols.sh_type != SHT_DYNSYM || symbols.sh_entsize != sizeof(Elf64_Sym)
                    || symbols.sh_link >= header->e_shnum || symbols.sh_offset > length
                    || symbols.sh_size > length - symbols.sh_offset) continue;
            const auto &strings = sections[symbols.sh_link];
            if (strings.sh_offset > length || strings.sh_size > length - strings.sh_offset) continue;
            const auto *entries = reinterpret_cast<const Elf64_Sym *>(bytes + symbols.sh_offset);
            const char *names = reinterpret_cast<const char *>(bytes + strings.sh_offset);
            for (size_t j = 0; j < symbols.sh_size / sizeof(Elf64_Sym); j++) {
                const auto &entry = entries[j];
                if (entry.st_shndx == SHN_UNDEF || entry.st_value == 0 || entry.st_name >= strings.sh_size) continue;
                const char *candidate = names + entry.st_name;
                if (!memchr(candidate, 0, strings.sh_size - entry.st_name)) continue;
                if (strcmp(candidate, name) == 0) {
                    found = reinterpret_cast<void *>(module.base + entry.st_value);
                    break;
                }
            }
        }
    }
    munmap(const_cast<uint8_t *>(bytes), length);
    return found;
}
static bool hookGuestSensitiveBinder() {
    LoadedBinderModule loaded{};
    dl_iterate_phdr(findLoadedBinder, &loaded);
    void *library = dlopen("libbinder.so", RTLD_NOW);
    if (!library) ALOGW("Sensitive Binder namespace dlopen unavailable: %s", dlerror());
    auto resolve = [&](const char *symbol) -> void * {
        void *address = library ? dlsym(library, symbol) : nullptr;
        return address ? address : loadedBinderSymbol(loaded, symbol);
    };
    void *target = resolve("_ZN7android8BpBinder8transactEjRKNS_6ParcelEPS1_j");
    parcelData = reinterpret_cast<const uint8_t *(*)(const void *)>(resolve("_ZNK7android6Parcel4dataEv"));
    parcelSize = reinterpret_cast<size_t (*)(const void *)>(resolve("_ZNK7android6Parcel8dataSizeEv"));
    if (!target || !parcelData || !parcelSize) {
        ALOGE("Sensitive Binder gate unavailable module=%s target=%d data=%d size=%d",
                loaded.path, target != nullptr, parcelData != nullptr, parcelSize != nullptr);
        return false;
    }
#if defined(__aarch64__)
    A64HookFunction(target, reinterpret_cast<void *>(guardedBpTransact),
                    reinterpret_cast<void **>(&originalBpTransact));
#else
    MSHookFunction(target, reinterpret_cast<void *>(guardedBpTransact),
                    reinterpret_cast<void **>(&originalBpTransact));
#endif
    bool installed = originalBpTransact != nullptr;
    ALOGI("Sensitive Binder gate installed=%d", installed);
    return installed;
}

static jint new_native_cameraNativeSetupFunc_T1(JNIEnv *env, jobject thiz, jobject camera_this,
                                                jint cameraId, jstring packageName) {
    if (!guestPermissionGranted(env, "android.permission.CAMERA")) return -13;

    jstring host = env->NewStringUTF(patchEnv.host_packageName);

    return patchEnv.orig_native_cameraNativeSetupFunc.t1(env, thiz, camera_this,
                                                         cameraId,
                                                         host);
}

static jint new_native_cameraNativeSetupFunc_T2(JNIEnv *env, jobject thiz, jobject camera_this,
                                                jint cameraId, jint halVersion,
                                                jstring packageName) {
    if (!guestPermissionGranted(env, "android.permission.CAMERA")) return -13;

    jstring host = env->NewStringUTF(patchEnv.host_packageName);

    return patchEnv.orig_native_cameraNativeSetupFunc.t2(env, thiz, camera_this, cameraId,
                                                         halVersion, host);
}

static jint new_native_cameraNativeSetupFunc_T3(JNIEnv *env, jobject thiz, jobject camera_this,
                                                jint cameraId, jint halVersion,
                                                jstring packageName, jboolean option) {
    if (!guestPermissionGranted(env, "android.permission.CAMERA")) return -13;

    jstring host = env->NewStringUTF(patchEnv.host_packageName);

    return patchEnv.orig_native_cameraNativeSetupFunc.t3(env, thiz, camera_this, cameraId,
                                                         halVersion, host, option);
}

static jint new_native_cameraNativeSetupFunc_T4(JNIEnv *env, jobject thiz, jobject camera_this,
                                                jint cameraId,
                                                jstring packageName, jboolean option) {
    if (!guestPermissionGranted(env, "android.permission.CAMERA")) return -13;

    jstring host = env->NewStringUTF(patchEnv.host_packageName);

    return patchEnv.orig_native_cameraNativeSetupFunc.t4(env, thiz, camera_this, cameraId, host,
                                                         option);
}


static jint
new_native_audioRecordNativeCheckPermission(JNIEnv *env, jobject thiz, jstring _packagename) {
    if (!guestPermissionGranted(env, "android.permission.RECORD_AUDIO")) return -13;
    jstring host = env->NewStringUTF(patchEnv.host_packageName);
    return patchEnv.orig_audioRecordNativeCheckPermission(env, thiz, host);
}


static void
new_bridge_cameraNativeSetupFunc(const void **args, void *pResult, const void *method, void *self) {
    // args[0] = this
    switch (patchEnv.cameraMethodType) {
        case 1:
            args[4] = patchEnv.GetStringFromCstr(patchEnv.host_packageName);
            break;
        case 2:
            args[5] = patchEnv.GetStringFromCstr(patchEnv.host_packageName);
            break;
        case 3:
            args[5] = patchEnv.GetStringFromCstr(patchEnv.host_packageName);
            break;
        case 4:
            args[4] = patchEnv.GetStringFromCstr(patchEnv.host_packageName);
            break;
    }
    patchEnv.orig_cameraNativeSetup_dvm(args, pResult, method, self);
}

void mark() {
    // Do nothing
};

static size_t getArtMethodAddress(jobject javaMethod, jmethodID methodId) {
    if (patchEnv.api_level < 30) {
        return (size_t) methodId;
    } else {
        JNIEnv *env = Environment::current();
        jclass executableClass = env->FindClass("java/lang/reflect/Executable");
        jfieldID artMethod = env->GetFieldID(executableClass, "artMethod", "J");
        jlong addr = env->GetLongField(javaMethod, artMethod);
        return addr;
    }
}

void measureNativeOffset(bool isArt) {

    jmethodID markMethod = nativeEngineClass->getStaticMethod<void(void)>("nativeMark").getId();

    jobject method = Environment::current()->ToReflectedMethod(nativeEngineClass.get(), markMethod, JNI_TRUE);
    size_t startAddress = (size_t) getArtMethodAddress(method, markMethod);
    size_t targetAddress = (size_t) mark;
    if (isArt && patchEnv.art_work_around_app_jni_bugs) {
        targetAddress = (size_t) patchEnv.art_work_around_app_jni_bugs;
    }

    int offset = 0;
    bool found = false;
    while (true) {
        if (*((size_t *) (startAddress + offset)) == targetAddress) {
            found = true;
            break;
        }
        offset += 4;
        if (offset >= 100) {
            ALOGE("Error: Unable to find the jni function.");
            break;
        }
    }
    if (found) {
        patchEnv.native_offset = offset;
        if (!isArt) {
            patchEnv.native_offset += (sizeof(int) + sizeof(void *));
        }
    }
}


inline void replaceGetCallingUid(jboolean isArt) {
    auto binderClass = findClassLocal("android/os/Binder");
    if (isArt) {
        size_t mtd_getCallingUid = (size_t) binderClass->getStaticMethod<jint(void)>(
                "getCallingUid").getId();
        int nativeFuncOffset = patchEnv.native_offset;
        void **jniFuncPtr = (void **) (mtd_getCallingUid + nativeFuncOffset);
        patchEnv.jni_orig_getCallingUid = (Function_getCallingUid) (*jniFuncPtr);
        *jniFuncPtr = (void *) getCallingUid;
    } else {
        binderClass->registerNatives({makeNativeMethod("getCallingUid", getCallingUid)});
    }
}

inline void
replaceOpenDexFileMethod(jobject javaMethod, jboolean isArt, int apiLevel) {
    jmethodID openDexNative = Environment::current()->FromReflectedMethod(javaMethod);
    size_t mtd_openDexNative = getArtMethodAddress(javaMethod, openDexNative);
    int nativeFuncOffset = patchEnv.native_offset;
    void **jniFuncPtr = (void **) (mtd_openDexNative + nativeFuncOffset);

    if (!isArt) {
        patchEnv.orig_openDexFile_dvm = (Function_DalvikBridgeFunc) (*jniFuncPtr);
        *jniFuncPtr = (void *) new_bridge_openDexNativeFunc;
    } else {
        if (apiLevel < 24) {
            patchEnv.orig_openDexNativeFunc_art.beforeN = (Function_openDexNativeFunc) (*jniFuncPtr);
            *jniFuncPtr = (void *) new_native_openDexNativeFunc;
        } else {
            patchEnv.orig_openDexNativeFunc_art.afterN = (Native_openDexNativeFunc_N) (*jniFuncPtr);
            *jniFuncPtr = (void *) new_native_openDexNativeFunc_N;
        }
    }

}


inline void
replaceCameraNativeSetupMethod(jobject javaMethod, jboolean isArt, int apiLevel) {

    if (!javaMethod) {
        return;
    }
    jmethodID cameraNativeSetup = Environment::current()->FromReflectedMethod(javaMethod);
    size_t mtd_cameraNativeSetup = getArtMethodAddress(javaMethod, cameraNativeSetup);
    int nativeFuncOffset = patchEnv.native_offset;
    void **jniFuncPtr = (void **) (mtd_cameraNativeSetup + nativeFuncOffset);

    if (!isArt) {
        patchEnv.orig_cameraNativeSetup_dvm = (Function_DalvikBridgeFunc) (*jniFuncPtr);
        *jniFuncPtr = (void *) new_bridge_cameraNativeSetupFunc;
    } else {
        switch (patchEnv.cameraMethodType) {
            case 1:
                patchEnv.orig_native_cameraNativeSetupFunc.t1 = (Function_cameraNativeSetupFunc_T1) (*jniFuncPtr);
                *jniFuncPtr = (void *) new_native_cameraNativeSetupFunc_T1;
                break;
            case 2:
                patchEnv.orig_native_cameraNativeSetupFunc.t2 = (Function_cameraNativeSetupFunc_T2) (*jniFuncPtr);
                *jniFuncPtr = (void *) new_native_cameraNativeSetupFunc_T2;
                break;
            case 3:
                patchEnv.orig_native_cameraNativeSetupFunc.t3 = (Function_cameraNativeSetupFunc_T3) (*jniFuncPtr);
                *jniFuncPtr = (void *) new_native_cameraNativeSetupFunc_T3;
                break;
            case 4:
                patchEnv.orig_native_cameraNativeSetupFunc.t4 = (Function_cameraNativeSetupFunc_T4) (*jniFuncPtr);
                *jniFuncPtr = (void *) new_native_cameraNativeSetupFunc_T4;
                break;
        }
    }

}


void
replaceAudioRecordNativeCheckPermission(jobject javaMethod, jboolean isArt, int api) {
    if (!javaMethod || !isArt) {
        return;
    }
    jmethodID methodStruct = Environment::current()->FromReflectedMethod(javaMethod);
    size_t mtd_methodStruct = getArtMethodAddress(javaMethod, methodStruct);
    void **funPtr = (void **) (mtd_methodStruct + patchEnv.native_offset);
    patchEnv.orig_audioRecordNativeCheckPermission = (Function_audioRecordNativeCheckPermission) (*funPtr);
    *funPtr = (void *) new_native_audioRecordNativeCheckPermission;
}


/**
 * Only called once.
 * @param javaMethod Method from Java
 * @param isArt Dalvik or Art
 * @param apiLevel Api level from Java
 */
void hookAndroidVM(JArrayClass<jobject> javaMethods,
                   jstring packageName, jboolean isArt, jint apiLevel,
                   jint cameraMethodType) {

    JNIEnv *env = Environment::current();

    JNINativeMethod methods[] = {
            NATIVE_METHOD((void *) mark, "nativeMark", "()V"),
    };
    if (env->RegisterNatives(nativeEngineClass.get(), methods, 1) < 0) {
        return;
    }

    // Android 12 changed ART's internal ArtMethod layout. The legacy offset-based patches below
    // corrupt Binder.getCallingUid's entry point on API 31 and crash before the guest Application
    // is created. Activity/Binder routing for the API 31 probe is already handled by the Java
    // transaction and service proxies, while dex output, camera and audio native patches are not
    // required to render LINE's logged-out screen. Keep the native I/O uniformer enabled, but do
    // not mutate ART method structures on Android 12+.
    if (!hookGuestSensitiveBinder()) {
        env->ThrowNew(env->FindClass("java/lang/SecurityException"), "Sensitive Binder gate unavailable");
        return;
    }
    if (apiLevel >= 31) {
        ALOGW("Skipping legacy ArtMethod patches on API %d", apiLevel);
        return;
    }
    patchEnv.is_art = isArt;
    patchEnv.cameraMethodType = cameraMethodType;
    patchEnv.host_packageName = (char *) env->GetStringUTFChars(packageName,
                                                                NULL);
    patchEnv.api_level = apiLevel;
    void *soInfo = getDvmOrArtSOHandle();
    patchEnv.method_onGetCallingUid = nativeEngineClass->getStaticMethod<jint(jint)>(
            "onGetCallingUid").getId();
    patchEnv.method_onOpenDexFileNative = env->GetStaticMethodID(nativeEngineClass.get(),
                                                                 "onOpenDexFileNative",
                                                                 "([Ljava/lang/String;)V");

    if (isArt) {
        patchEnv.art_work_around_app_jni_bugs = dlsym(soInfo, "art_work_around_app_jni_bugs");
    } else {
        // workaround for dlsym returns null when system has libhoudini
        void *h = dlopen("/system/lib/libandroid_runtime.so", RTLD_LAZY);
        {
            patchEnv.IPCThreadState_self = (int (*)(void)) dlsym(RTLD_DEFAULT,
                                                                 "_ZN7android14IPCThreadState4selfEv");
            patchEnv.native_getCallingUid = (int (*)(int)) dlsym(RTLD_DEFAULT,
                                                                 "_ZNK7android14IPCThreadState13getCallingUidEv");
            if (patchEnv.IPCThreadState_self == NULL) {
                patchEnv.IPCThreadState_self = (int (*)(void)) dlsym(RTLD_DEFAULT,
                                                                     "_ZN7android14IPCThreadState13getCallingUidEv");
            }
        }
        if (h != NULL) {
            dlclose(h);
        }

        patchEnv.GetCstrFromString = (char *(*)(void *)) dlsym(soInfo,
                                                               "_Z23dvmCreateCstrFromStringPK12StringObject");
        if (!patchEnv.GetCstrFromString) {
            patchEnv.GetCstrFromString = (char *(*)(void *)) dlsym(soInfo,
                                                                   "dvmCreateCstrFromString");
        }
        patchEnv.GetStringFromCstr = (void *(*)(const char *)) dlsym(soInfo,
                                                                     "_Z23dvmCreateStringFromCstrPKc");
        if (!patchEnv.GetStringFromCstr) {
            patchEnv.GetStringFromCstr = (void *(*)(const char *)) dlsym(soInfo,
                                                                         "dvmCreateStringFromCstr");
        }
    }
    measureNativeOffset(isArt);
    replaceGetCallingUid(isArt);

    replaceOpenDexFileMethod(javaMethods.getElement(OPEN_DEX).get(), isArt,
                             apiLevel);
    replaceCameraNativeSetupMethod(javaMethods.getElement(CAMERA_SETUP).get(),
                                   isArt, apiLevel);
    replaceAudioRecordNativeCheckPermission(javaMethods.getElement(
            AUDIO_NATIVE_CHECK_PERMISSION).get(),
                                            isArt, apiLevel);
}

bool processNothing(void* thiz, void* new_methods){ return true; }
bool (*orig_ProcessProfilingInfo)(void*, void*);

bool compileNothing(void* thiz, void* thread, void* method, bool osr) { return false; }
bool (*orig_CompileNothing)(void* thiz, void* thread, void* method, bool osr);

void (*org_notifyJitActivity)(void *);
void notifyNothing(void *thiz) {
    return;
}

void disableJit(int apiLevel) {
#ifdef __arm__
    void *libart = fake_dlopen("/system/lib/libart.so", RTLD_NOW);
    if (libart) {
        // disable profile.
        void *processProfilingInfo = NULL;
        const char *processProfileInfoFunc =
                apiLevel < 26 ? "_ZN3art12ProfileSaver20ProcessProfilingInfoEPt" :
                "_ZN3art12ProfileSaver20ProcessProfilingInfoEbPt";
        processProfilingInfo = fake_dlsym(libart, processProfileInfoFunc);
        ALOGE("processProfileingInfo: %p", processProfilingInfo);
        if (processProfilingInfo) {
            MSHookFunction(processProfilingInfo, (void*)processNothing, (void**)&orig_ProcessProfilingInfo);
        }

        // disable jit
        void *compileMethod = NULL;
        compileMethod = fake_dlsym(libart,
                                   "_ZN3art3jit3Jit13CompileMethodEPNS_9ArtMethodEPNS_6ThreadEb");
        ALOGE("compileMethod: %p", compileMethod);
        if (compileMethod) {
            MSHookFunction(compileMethod, (void*) compileNothing, (void**) &orig_CompileNothing);
        }

        void *notifyJitActivity = fake_dlsym(libart, "_ZN3art12ProfileSaver17NotifyJitActivityEv");
        if (notifyJitActivity) {
            MSHookFunction(notifyJitActivity, (void *) notifyNothing,
                          (void **) &org_notifyJitActivity);
        }
    }
#endif
}

void *getDvmOrArtSOHandle() {
    char so_name[25] = {0};
    __system_property_get("persist.sys.dalvik.vm.lib.2", so_name);
    if (strlen(so_name) == 0) {
        __system_property_get("persist.sys.dalvik.vm.lib", so_name);
    }
    void *soInfo = dlopen(so_name, 0);
    if (!soInfo) {
        soInfo = RTLD_DEFAULT;
    }
    return soInfo;
}
