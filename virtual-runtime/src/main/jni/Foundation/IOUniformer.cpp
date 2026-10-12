//
// VirtualApp Native Project
//
#include <unistd.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <utime.h>
#include <sys/time.h>
#include <android/dlext.h>
#include <atomic>
#include <mutex>
#include <string>
#include <fb/include/fb/ALog.h>

#ifdef __aarch64__
#include "A64Inlinehook/And64InlineHook.hpp"
#else
#include <Substrate/SubstrateHook.h>
#endif

//extern "C" {
//#include <HookZz/include/hookzz.h>
//}


#include "IOUniformer.h"
#include "SandboxFs.h"
#include "Path.h"
#include "SharedMediaPolicy.h"
#include "SymbolFinder.h"

static SharedMedia::Policy shared_media_policy;
static bool ensure_shared_media_hooks();

bool IOUniformer::sharedMediaAccess(const char *path, bool read, bool write) {
    return shared_media_policy.add(path, read, write);
}

static bool shared_fd_allowed(int fd, bool read, bool write) {
    if (shared_media_policy.empty()) return true;
    char name[64], target[4096];
    snprintf(name, sizeof(name), "/proc/self/fd/%d", fd);
    ssize_t size = syscall(__NR_readlinkat, AT_FDCWD, name, target, sizeof(target)-1);
    if (size <= 0) return true; // Invalid/non-file descriptors retain kernel validation.
    target[size] = 0;
    if (target[0] != '/') return true; // pipes, sockets and anonymous descriptors
    return shared_media_policy.allows(target, AT_FDCWD, read, write);
}

static const char *shared_relocate(const char *path, int *result, int dirfd,
                                   bool read, bool write) {
    if (shared_media_policy.empty()) return relocate_path(path, result);
    if (!path) {
        *result = NOT_MATCH;
        if (!shared_fd_allowed(dirfd, read, write)) { *result = FORBID; errno = EACCES; return nullptr; }
        return relocate_path(path, result);
    }
    // Normalize relative input before relocation, so a dirfd cannot skip redirects.
    std::string absolute = SharedMedia::resolve(path, dirfd, false);
    if (absolute.empty()) { *result = FORBID; errno = EACCES; return path; }
    const char *moved = relocate_path(absolute.c_str(), result);
    if (*result == FORBID) { errno = EACCES; return path; }
    if (!shared_media_policy.allows(moved, AT_FDCWD, read, write)) {
        if (moved != absolute.c_str()) free((void *)moved);
        *result = FORBID; errno = EACCES; return path;
    }
    char *owned = strdup(moved);
    if (moved != absolute.c_str()) free((void *)moved);
    return owned;
}

// Bounded route diagnostics contain fixed classifications, never actual paths.
static const char *shared_route_class(const char *path) {
    if (!path) return "unavailable";
    if (strstr(path, "/Android/data/") != nullptr) return "private-external";
    if (strncmp(path, "/storage/", 9) == 0) return "storage";
    if (strncmp(path, "/mnt/", 5) == 0) return "mount";
    if (strncmp(path, "/data/", 6) == 0) return "private-data";
    if (strncmp(path, "/proc/", 6) == 0) return "proc";
    return "other";
}

bool iu_loaded = false;

static std::atomic<int> uid_override(-1);
static bool uid_hook_installed = false;
static std::mutex uniformer_start_mutex;
static bool uniformer_started = false;
static std::string proc_maps_host_package;
static std::string proc_guest_process_name;

static bool is_proc_cmdline_path(const char *pathname) {
    if (pathname == nullptr) {
        return false;
    }
    if (strcmp(pathname, "/proc/self/cmdline") == 0 ||
        strcmp(pathname, "/proc/thread-self/cmdline") == 0) {
        return true;
    }
    char process_cmdline[64];
    snprintf(process_cmdline, sizeof(process_cmdline), "/proc/%d/cmdline", getpid());
    return strcmp(pathname, process_cmdline) == 0;
}

static int open_guest_proc_cmdline(int requested_flags) {
    if (proc_guest_process_name.empty()) {
        errno = ENOENT;
        return -1;
    }
#if defined(__NR_memfd_create)
    int staging = static_cast<int>(syscall(__NR_memfd_create, "cmdline", MFD_CLOEXEC));
#else
    int staging = -1;
    errno = ENOSYS;
#endif
    if (staging < 0) {
        return -1;
    }
    std::string contents(proc_guest_process_name);
    contents.push_back('\0');
    size_t written = 0;
    while (written < contents.size()) {
        ssize_t count = static_cast<ssize_t>(syscall(
                __NR_write, staging, contents.data() + written, contents.size() - written));
        if (count <= 0) {
            int saved_errno = errno;
            syscall(__NR_close, staging);
            errno = saved_errno;
            return -1;
        }
        written += static_cast<size_t>(count);
    }
    syscall(__NR_lseek, staging, 0, SEEK_SET);
    if ((requested_flags & O_CLOEXEC) == 0) {
        syscall(__NR_fcntl, staging, F_SETFD, 0);
    }
    return staging;
}

static bool is_proc_maps_path(const char *pathname) {
    if (pathname == nullptr) {
        return false;
    }
    if (strcmp(pathname, "/proc/self/maps") == 0 ||
        strcmp(pathname, "/proc/thread-self/maps") == 0) {
        return true;
    }
    char process_maps[64];
    snprintf(process_maps, sizeof(process_maps), "/proc/%d/maps", getpid());
    return strcmp(pathname, process_maps) == 0;
}

static bool is_proc_maps_leak(const char *line, size_t length) {
    std::string mapping(line, length);
    return (!proc_maps_host_package.empty() &&
            mapping.find(proc_maps_host_package) != std::string::npos) ||
           mapping.find("libva++.so") != std::string::npos ||
           mapping.find("/AppTwin/") != std::string::npos;
}

static int open_sanitized_proc_maps(int requested_flags) {
    int source = static_cast<int>(syscall(__NR_openat, AT_FDCWD, "/proc/self/maps",
                                          O_RDONLY | O_CLOEXEC, 0));
    if (source < 0) {
        ALOGE("proc-maps-sanitizer source-open errno=%d", errno);
        return -1;
    }

    std::string contents;
    char buffer[8192];
    while (true) {
        ssize_t count = static_cast<ssize_t>(syscall(__NR_read, source, buffer, sizeof(buffer)));
        if (count == 0) {
            break;
        }
        if (count < 0) {
            int saved_errno = errno;
            syscall(__NR_close, source);
            ALOGE("proc-maps-sanitizer source-read errno=%d", saved_errno);
            errno = saved_errno;
            return -1;
        }
        contents.append(buffer, static_cast<size_t>(count));
    }
    syscall(__NR_close, source);

#if defined(__NR_memfd_create)
    int staging = static_cast<int>(syscall(__NR_memfd_create, "jit-cache", MFD_CLOEXEC));
#else
    int staging = -1;
    errno = ENOSYS;
#endif
    if (staging < 0) {
        ALOGE("proc-maps-sanitizer memfd-create errno=%d", errno);
        return -1;
    }

    size_t start = 0;
    while (start < contents.size()) {
        size_t newline = contents.find('\n', start);
        size_t end = newline == std::string::npos ? contents.size() : newline + 1;
        if (!is_proc_maps_leak(contents.data() + start, end - start)) {
            size_t written = 0;
            while (written < end - start) {
                ssize_t count = static_cast<ssize_t>(syscall(
                        __NR_write, staging, contents.data() + start + written,
                        end - start - written));
                if (count <= 0) {
                    int saved_errno = errno;
                    syscall(__NR_close, staging);
                    ALOGE("proc-maps-sanitizer staging-write errno=%d", saved_errno);
                    errno = saved_errno;
                    return -1;
                }
                written += static_cast<size_t>(count);
            }
        }
        start = end;
    }
    syscall(__NR_lseek, staging, 0, SEEK_SET);

    if ((requested_flags & O_CLOEXEC) == 0) {
        syscall(__NR_fcntl, staging, F_SETFD, 0);
    }
    return staging;
}

void IOUniformer::init_env_before_all() {
    if (iu_loaded)
        return;
    char *api_level_chars = getenv("V_API_LEVEL");
    char *preview_api_level_chars = getenv("V_PREVIEW_API_LEVEL");
    if (api_level_chars) {
        ALOGE("Enter init before all.");
        int api_level = atoi(api_level_chars);
        int preview_api_level;
        preview_api_level = atoi(preview_api_level_chars);
        char keep_env_name[25];
        char forbid_env_name[25];
        char replace_src_env_name[25];
        char replace_dst_env_name[25];
        int i = 0;
        while (true) {
            sprintf(keep_env_name, "V_KEEP_ITEM_%d", i);
            char *item = getenv(keep_env_name);
            if (!item) {
                break;
            }
            add_keep_item(item);
            i++;
        }
        i = 0;
        while (true) {
            sprintf(forbid_env_name, "V_FORBID_ITEM_%d", i);
            char *item = getenv(forbid_env_name);
            if (!item) {
                break;
            }
            add_forbidden_item(item);
            i++;
        }
        i = 0;
        while (true) {
            sprintf(replace_src_env_name, "V_REPLACE_ITEM_SRC_%d", i);
            char *item_src = getenv(replace_src_env_name);
            if (!item_src) {
                break;
            }
            sprintf(replace_dst_env_name, "V_REPLACE_ITEM_DST_%d", i);
            char *item_dst = getenv(replace_dst_env_name);
            add_replace_item(item_src, item_dst);
            i++;
        }
        startUniformer(getenv("V_SO_PATH"), getenv("V_HOST_PACKAGE"),
                       getenv("V_GUEST_PROCESS"), api_level, preview_api_level);
        char *uid_override_chars = getenv("V_REPORTED_UID");
        if (uid_override_chars) {
            configureUidOverride(atoi(uid_override_chars));
        }
        iu_loaded = true;
    }
}

static inline void
hook_function(void *addr, void *new_func, void **old_func) {
    static std::mutex hook_mutex;
    static std::map<void *, void *> installed;
    std::lock_guard<std::mutex> lock(hook_mutex);
    auto found = installed.find(addr);
    if (found != installed.end()) { *old_func = found->second; return; }
#ifdef __aarch64__
    A64HookFunction(addr, new_func, old_func);
#else
    MSHookFunction(addr, new_func, old_func);
#endif
    if (*old_func != nullptr) installed[addr] = *old_func;
}

static inline void
hook_function(void *handle, const char *symbol, void *new_func, void **old_func) {
    const bool shared_diagnostic = !shared_media_policy.empty();
    if (shared_diagnostic) ALOGI("SharedMediaHooks symbol=%s phase=resolve", symbol);
    void *addr = dlsym(handle, symbol);
    if (addr == NULL) {
        if (shared_diagnostic) ALOGI("SharedMediaHooks symbol=%s phase=missing", symbol);
        return;
    }
    if (shared_diagnostic) ALOGI("SharedMediaHooks symbol=%s phase=install", symbol);
    hook_function(addr, new_func, old_func);
    if (shared_diagnostic) ALOGI("SharedMediaHooks symbol=%s phase=installed ready=%d", symbol, *old_func != nullptr);
}

static bool should_hook_android_dlopen_ext(int api_level, const char *guest_process_name) {
    static constexpr int ANDROID_17_API_LEVEL = 37;
    static constexpr char FACEBOOK_PACKAGE[] = "com.facebook.katana";
    if (api_level < ANDROID_17_API_LEVEL || guest_process_name == nullptr) {
        return true;
    }
    size_t package_length = strlen(FACEBOOK_PACKAGE);
    return strncmp(guest_process_name, FACEBOOK_PACKAGE, package_length) != 0
           || (guest_process_name[package_length] != '\0'
               && guest_process_name[package_length] != ':');
}


void onSoLoaded(const char *name, void *handle);

void IOUniformer::redirect(const char *orig_path, const char *new_path) {
    add_replace_item(orig_path, new_path);
}

const char *IOUniformer::query(const char *orig_path) {
    int result;
    return relocate_path(orig_path, &result);
}

void IOUniformer::whitelist(const char *_path) {
    add_keep_item(_path);
}

void IOUniformer::forbid(const char *_path) {
    add_forbidden_item(_path);
}


const char *IOUniformer::reverse(const char *_path) {
    return reverse_relocate_path(_path);
}


__BEGIN_DECLS

HOOK_DEF(uid_t, getuid) {
    int configured = uid_override.load();
    return configured >= 0 ? static_cast<uid_t>(configured) : orig_getuid();
}

#define FREE(ptr, org_ptr) { if ((void*) ptr != NULL && (void*) ptr != (void*) org_ptr) { free((void*) ptr); } }
#define RETURN_IF_FORBID if(res == FORBID) return -1;


HOOK_DEF(int, access, const char *pathname, int mode) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, (mode & R_OK) != 0, (mode & W_OK) != 0);
    RETURN_IF_FORBID
    int ret = syscall(__NR_faccessat, AT_FDCWD, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, chmod, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, false, true);
    RETURN_IF_FORBID
    int ret = orig_chmod(redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, fchmodat, int dirfd, const char *pathname, mode_t mode, int flags) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, false, true);
    RETURN_IF_FORBID
    int ret = orig_fchmodat(dirfd, redirect_path, mode, flags);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(jstring, JVM_NativeLoad, JNIEnv *env, jstring filename, jobject loader,
         jclass caller) {
    if (filename == nullptr) {
        return orig_JVM_NativeLoad(env, filename, loader, caller);
    }
    const char *original_path = env->GetStringUTFChars(filename, nullptr);
    if (original_path == nullptr) {
        return orig_JVM_NativeLoad(env, filename, loader, caller);
    }
    int res;
    const char *redirect_path = relocate_path(original_path, &res);
    if (res == FORBID) {
        env->ReleaseStringUTFChars(filename, original_path);
        return env->NewStringUTF("Guest native library path is forbidden");
    }
    jstring redirected_filename = env->NewStringUTF(redirect_path);
    jstring result = orig_JVM_NativeLoad(env, redirected_filename, loader, caller);
    env->DeleteLocalRef(redirected_filename);
    FREE(redirect_path, original_path);
    env->ReleaseStringUTFChars(filename, original_path);
    return result;
}


HOOK_DEF(void *, android_dlopen_ext, const char *filename, int flags,
         const android_dlextinfo *extinfo) {
    if (filename == nullptr) {
        return orig_android_dlopen_ext(filename, flags, extinfo);
    }
    int res;
    const char *redirect_path = relocate_path(filename, &res);
    if (res == FORBID) {
        errno = EACCES;
        return nullptr;
    }
    using LoaderDlopen = void *(*)(const char *, int, const android_dlextinfo *, const void *);
    LoaderDlopen loader_dlopen = reinterpret_cast<LoaderDlopen>(
            dlsym(RTLD_DEFAULT, "__loader_android_dlopen_ext"));
    void *result;
    if (loader_dlopen != nullptr) {
        result = loader_dlopen(redirect_path, flags, extinfo,
                __builtin_return_address(0));
    } else {
        result = orig_android_dlopen_ext(redirect_path, flags, extinfo);
    }
    FREE(redirect_path, filename);
    return result;
}


HOOK_DEF(int, stat, const char *pathname, struct stat *buf) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, true, false);
    RETURN_IF_FORBID
    int ret = syscall(__NR_newfstatat, AT_FDCWD, redirect_path, buf, 0);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, lstat, const char *pathname, struct stat *buf) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, true, false);
    RETURN_IF_FORBID
    int ret = syscall(__NR_newfstatat, AT_FDCWD, redirect_path, buf, AT_SYMLINK_NOFOLLOW);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, open, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    if ((flags & O_ACCMODE) == O_RDONLY && is_proc_cmdline_path(pathname)) {
        int guest_cmdline = open_guest_proc_cmdline(flags);
        if (guest_cmdline >= 0) {
            return guest_cmdline;
        }
    }
    if ((flags & O_ACCMODE) == O_RDONLY && is_proc_maps_path(pathname)) {
        int sanitized = open_sanitized_proc_maps(flags);
        if (sanitized >= 0) {
            return sanitized;
        }
    }
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, SharedMedia::openReads(flags), SharedMedia::openWrites(flags));
    RETURN_IF_FORBID
    int ret = syscall(__NR_openat, AT_FDCWD, redirect_path, flags, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, openat, int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    if ((flags & O_ACCMODE) == O_RDONLY && is_proc_cmdline_path(pathname)) {
        int guest_cmdline = open_guest_proc_cmdline(flags);
        if (guest_cmdline >= 0) {
            return guest_cmdline;
        }
    }
    if ((flags & O_ACCMODE) == O_RDONLY && is_proc_maps_path(pathname)) {
        int sanitized = open_sanitized_proc_maps(flags);
        if (sanitized >= 0) {
            return sanitized;
        }
    }
    static std::atomic<unsigned int> diagnostic_count(0);
    const bool diagnostic = pathname && pathname[0] != '/' && (flags & O_TRUNC) != 0
                            && !shared_media_policy.empty()
                            && diagnostic_count.fetch_add(1, std::memory_order_relaxed) < 8;
    char fd_target[4096] = {};
    std::string absolute;
    int descriptor_flags = -1;
    if (diagnostic) {
        char descriptor_name[64];
        snprintf(descriptor_name, sizeof(descriptor_name), "/proc/self/fd/%d", dirfd);
        ssize_t count = syscall(__NR_readlinkat, AT_FDCWD, descriptor_name, fd_target, sizeof(fd_target)-1);
        if (count > 0) fd_target[count] = 0;
        absolute = SharedMedia::resolve(pathname, dirfd, false);
        descriptor_flags = fcntl(dirfd, F_GETFL);
    }
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, SharedMedia::openReads(flags), SharedMedia::openWrites(flags));
    if (res == FORBID) {
        int saved_errno = errno;
        if (diagnostic) ALOGI("SharedMediaRoute raw=%s resolved=%s opath=%d result=%d changed=0 sourceProtected=%d targetProtected=0 errno=%d",
                              shared_route_class(fd_target), shared_route_class(absolute.c_str()),
                              descriptor_flags >= 0 && (descriptor_flags & O_PATH) != 0, res,
                              shared_media_policy.protects(absolute.c_str()), saved_errno);
        errno = saved_errno;
        return -1;
    }
    int ret = syscall(__NR_openat, dirfd, redirect_path, flags, mode);
    int saved_errno = errno;
    if (diagnostic) ALOGI("SharedMediaRoute raw=%s resolved=%s opath=%d result=%d changed=%d sourceProtected=%d targetProtected=%d errno=%d",
                          shared_route_class(fd_target), shared_route_class(absolute.c_str()),
                          descriptor_flags >= 0 && (descriptor_flags & O_PATH) != 0, res,
                          absolute != redirect_path, shared_media_policy.protects(absolute.c_str()),
                          shared_media_policy.protects(redirect_path), ret < 0 ? saved_errno : 0);
    FREE(redirect_path, pathname);
    errno = saved_errno;
    return ret;
}


// Bionic's fortified headers route two-argument open/openat calls through these
// distinct symbols. Native guest libraries use both entry points, so hooking only
// open/openat lets reads of canonical guest data paths escape the redirect layer.
HOOK_DEF(int, __open_2, const char *pathname, int flags) {
    return new_open(pathname, flags);
}


HOOK_DEF(int, __openat_2, int dirfd, const char *pathname, int flags) {
    return new_openat(dirfd, pathname, flags);
}


HOOK_DEF(FILE *, fopen, const char *pathname, const char *mode) {
    if (mode != nullptr && mode[0] == 'r' && is_proc_cmdline_path(pathname)) {
        int guest_cmdline = open_guest_proc_cmdline(O_CLOEXEC);
        if (guest_cmdline >= 0) {
            FILE *stream = fdopen(guest_cmdline, mode);
            if (stream != nullptr) {
                return stream;
            }
            int saved_errno = errno;
            syscall(__NR_close, guest_cmdline);
            errno = saved_errno;
        }
    }
    if (mode != nullptr && mode[0] == 'r' && is_proc_maps_path(pathname)) {
        int sanitized = open_sanitized_proc_maps(O_CLOEXEC);
        if (sanitized >= 0) {
            FILE *stream = fdopen(sanitized, mode);
            if (stream != nullptr) {
                return stream;
            }
            int saved_errno = errno;
            syscall(__NR_close, sanitized);
            errno = saved_errno;
        }
    }
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, (mode && (mode[0] == 'r' || strchr(mode, '+'))), (mode && (mode[0] != 'r' || strchr(mode, '+'))));
    if (res == FORBID) {
        errno = EACCES;
        return nullptr;
    }
    FILE *ret = orig_fopen(redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(DIR *, opendir, const char *pathname) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, true, false);
    if (res == FORBID) {
        errno = EACCES;
        return nullptr;
    }
    DIR *ret = orig_opendir(redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, mkdir, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_mkdirat, AT_FDCWD, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, rename, const char *oldpath, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = shared_relocate(oldpath, &res_old, AT_FDCWD, false, true);
    const char *redirect_path_new = shared_relocate(newpath, &res_new, AT_FDCWD, false, true);
    if (res_old == FORBID || res_new == FORBID) {
        FREE(redirect_path_old, oldpath);
        FREE(redirect_path_new, newpath);
        errno = EACCES; return -1;
    }
    int ret = syscall(__NR_renameat, AT_FDCWD, redirect_path_old,
                      AT_FDCWD, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


HOOK_DEF(int, unlink, const char *pathname) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_unlinkat, AT_FDCWD, redirect_path, 0);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, rmdir, const char *pathname) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_unlinkat, AT_FDCWD, redirect_path, AT_REMOVEDIR);
    FREE(redirect_path, pathname);
    return ret;
}



// int fstatat64(int dirfd, const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstatat64, int dirfd, const char *pathname, struct stat *buf, int flags) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, true, false);
    RETURN_IF_FORBID
    int ret = syscall(__NR_newfstatat, dirfd, redirect_path, buf, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev);
HOOK_DEF(int, mknodat, int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_mknodat, dirfd, redirect_path, mode, dev);
    FREE(redirect_path, pathname);
    return ret;
}


// int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags);
HOOK_DEF(int, utimensat, int dirfd, const char *pathname, const struct timespec times[2],
         int flags) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_utimensat, dirfd, redirect_path, times, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags);
HOOK_DEF(int, fchownat, int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_fchownat, dirfd, redirect_path, owner, group, flags);
    FREE(redirect_path, pathname);
    return ret;
}

// int chroot(const char *pathname);
HOOK_DEF(int, chroot, const char *pathname) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, true, false);
    RETURN_IF_FORBID
    int ret = syscall(__NR_chroot, redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


// int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, renameat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = shared_relocate(oldpath, &res_old, olddirfd, false, true);
    const char *redirect_path_new = shared_relocate(newpath, &res_new, newdirfd, false, true);
    if (res_old == FORBID || res_new == FORBID) {
        FREE(redirect_path_old, oldpath);
        FREE(redirect_path_new, newpath);
        errno = EACCES; return -1;
    }
    int ret = syscall(__NR_renameat, olddirfd, redirect_path_old, newdirfd, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


// int unlinkat(int dirfd, const char *pathname, int flags);
HOOK_DEF(int, unlinkat, int dirfd, const char *pathname, int flags) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_unlinkat, dirfd, redirect_path, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int symlinkat(const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, symlinkat, const char *oldpath, int newdirfd, const char *newpath) {
    if (shared_media_policy.empty()) {
        int res_old, res_new;
        const char *redirect_path_old = relocate_path(oldpath, &res_old);
        const char *redirect_path_new = relocate_path(newpath, &res_new);
        if (res_old == FORBID || res_new == FORBID) {
            FREE(redirect_path_old, oldpath); FREE(redirect_path_new, newpath);
            errno = EACCES; return -1;
        }
        int ret = syscall(__NR_symlinkat, redirect_path_old, newdirfd, redirect_path_new);
        FREE(redirect_path_old, oldpath); FREE(redirect_path_new, newpath);
        return ret;
    }

    int res_new;
    const char *redirect_path_new = shared_relocate(newpath, &res_new, newdirfd, false, true);
    if (res_new == FORBID) { errno = EACCES; return -1; }
    // A relative symlink target is interpreted relative to the link's parent,
    // rather than the caller's cwd or the directory fd itself.
    std::string target;
    if (oldpath && oldpath[0] != '/') {
        std::string linkPath(redirect_path_new);
        target = linkPath.substr(0, linkPath.rfind('/') + 1) + oldpath;
    } else if (oldpath) target = oldpath;
    int res_old;
    const char *redirect_path_old = shared_relocate(oldpath ? target.c_str() : nullptr,
                                                   &res_old, AT_FDCWD, false, true);
    if (res_old == FORBID) {
        FREE(redirect_path_new, newpath);
        FREE(redirect_path_old, target.c_str());
        errno = EACCES; return -1;
    }
    const char *storedTarget = oldpath && oldpath[0] != '/' ? oldpath : redirect_path_old;
    int ret = syscall(__NR_symlinkat, storedTarget, newdirfd, redirect_path_new);
    FREE(redirect_path_old, target.c_str());
    FREE(redirect_path_new, newpath);
    return ret;
}

// int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags);
HOOK_DEF(int, linkat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
         int flags) {
    int res_old;
    int res_new;
    const char *redirect_path_old = shared_relocate(oldpath, &res_old, olddirfd, false, true);
    const char *redirect_path_new = shared_relocate(newpath, &res_new, newdirfd, false, true);
    if (res_old == FORBID || res_new == FORBID) {
        FREE(redirect_path_old, oldpath);
        FREE(redirect_path_new, newpath);
        errno = EACCES; return -1;
    }
    int ret = syscall(__NR_linkat, olddirfd, redirect_path_old, newdirfd, redirect_path_new, flags);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


// int mkdirat(int dirfd, const char *pathname, mode_t mode);
HOOK_DEF(int, mkdirat, int dirfd, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_mkdirat, dirfd, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


// int readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
HOOK_DEF(int, readlinkat, int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, dirfd, true, false);
    RETURN_IF_FORBID
    int ret = syscall(__NR_readlinkat, dirfd, redirect_path, buf, bufsiz);
    FREE(redirect_path, pathname);
    return ret;
}

// int truncate(const char *path, off_t length);
HOOK_DEF(int, truncate, const char *pathname, off_t length) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, false, true);
    RETURN_IF_FORBID
    int ret = syscall(__NR_truncate, redirect_path, length);
    FREE(redirect_path, pathname);
    return ret;
}

// int chdir(const char *path);
// Descriptor operations also protect descriptors inherited or supplied by a provider.
#define SHARED_FD_GUARD(fd, read, write, failure) \
    if (!shared_fd_allowed(fd, read, write)) { errno = EACCES; return failure; }
HOOK_DEF(ssize_t, read, int fd, void *buffer, size_t size) {
    SHARED_FD_GUARD(fd, true, false, -1)
    return orig_read(fd, buffer, size);
}
HOOK_DEF(ssize_t, write, int fd, const void *buffer, size_t size) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_write(fd, buffer, size);
}
HOOK_DEF(ssize_t, pread64, int fd, void *buffer, size_t size, off64_t offset) {
    SHARED_FD_GUARD(fd, true, false, -1)
    return orig_pread64(fd, buffer, size, offset);
}
HOOK_DEF(ssize_t, pwrite64, int fd, const void *buffer, size_t size, off64_t offset) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_pwrite64(fd, buffer, size, offset);
}
HOOK_DEF(ssize_t, readv, int fd, const struct iovec *iov, int count) {
    SHARED_FD_GUARD(fd, true, false, -1)
    return orig_readv(fd, iov, count);
}
HOOK_DEF(ssize_t, writev, int fd, const struct iovec *iov, int count) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_writev(fd, iov, count);
}
HOOK_DEF(ssize_t, preadv, int fd, const struct iovec *iov, int count, off_t offset) {
    SHARED_FD_GUARD(fd, true, false, -1)
    return orig_preadv(fd, iov, count, offset);
}
HOOK_DEF(ssize_t, pwritev, int fd, const struct iovec *iov, int count, off_t offset) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_pwritev(fd, iov, count, offset);
}
HOOK_DEF(ssize_t, pread, int fd, void *buffer, size_t size, off_t offset) {
    SHARED_FD_GUARD(fd, true, false, -1)
    return orig_pread(fd, buffer, size, offset);
}
HOOK_DEF(ssize_t, pwrite, int fd, const void *buffer, size_t size, off_t offset) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_pwrite(fd, buffer, size, offset);
}
HOOK_DEF(int, futimens, int fd, const struct timespec times[2]) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_futimens(fd, times);
}
HOOK_DEF(int, utime, const char *path, const struct utimbuf *times) {
    int res;
    const char *redirect_path = shared_relocate(path, &res, AT_FDCWD, false, true);
    RETURN_IF_FORBID
    int ret = orig_utime(redirect_path, times);
    FREE(redirect_path, path); return ret;
}
HOOK_DEF(int, utimes, const char *path, const struct timeval times[2]) {
    int res;
    const char *redirect_path = shared_relocate(path, &res, AT_FDCWD, false, true);
    RETURN_IF_FORBID
    int ret = orig_utimes(redirect_path, times);
    FREE(redirect_path, path); return ret;
}

HOOK_DEF(int, ftruncate, int fd, off_t length) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_ftruncate(fd, length);
}
HOOK_DEF(int, ftruncate64, int fd, off64_t length) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_ftruncate64(fd, length);
}
HOOK_DEF(int, fchmod, int fd, mode_t mode) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_fchmod(fd, mode);
}
HOOK_DEF(int, fchown, int fd, uid_t owner, gid_t group) {
    SHARED_FD_GUARD(fd, false, true, -1)
    return orig_fchown(fd, owner, group);
}
HOOK_DEF(void *, mmap, void *address, size_t length, int protection, int flags, int fd, off_t offset) {
    if (!(flags & MAP_ANONYMOUS)) {
        // A PROT_NONE mapping can later become readable via mprotect. A writable
        // descriptor can similarly elevate a shared mapping, so gate its maximum
        // kernel capability rather than only the initial protection bits.
        int descriptorFlags = fcntl(fd, F_GETFL);
        bool sharedWritable = (flags & MAP_SHARED) && descriptorFlags >= 0
                              && (descriptorFlags & O_ACCMODE) != O_RDONLY;
        SHARED_FD_GUARD(fd, true, sharedWritable, MAP_FAILED)
    }
    return orig_mmap(address, length, protection, flags, fd, offset);
}
HOOK_DEF(void *, mmap64, void *address, size_t length, int protection, int flags, int fd, off64_t offset) {
    if (!(flags & MAP_ANONYMOUS)) {
        int descriptorFlags = fcntl(fd, F_GETFL);
        bool sharedWritable = (flags & MAP_SHARED) && descriptorFlags >= 0
                              && (descriptorFlags & O_ACCMODE) != O_RDONLY;
        SHARED_FD_GUARD(fd, true, sharedWritable, MAP_FAILED)
    }
    return orig_mmap64(address, length, protection, flags, fd, offset);
}

HOOK_DEF(int, creat, const char *path, mode_t mode) { return new_open(path, O_WRONLY | O_CREAT | O_TRUNC, mode); }
HOOK_DEF(int, truncate64, const char *path, off64_t length) {
    int res;
    const char *redirect_path = shared_relocate(path, &res, AT_FDCWD, false, true);
    RETURN_IF_FORBID
    int ret = orig_truncate64(redirect_path, length);
    FREE(redirect_path, path);
    return ret;
}
HOOK_DEF(int, chown, const char *path, uid_t owner, gid_t group) { return new_fchownat(AT_FDCWD, path, owner, group, 0); }
HOOK_DEF(int, lchown, const char *path, uid_t owner, gid_t group) { return new_fchownat(AT_FDCWD, path, owner, group, AT_SYMLINK_NOFOLLOW); }
HOOK_DEF(int, link, const char *oldpath, const char *newpath) { return new_linkat(AT_FDCWD, oldpath, AT_FDCWD, newpath, 0); }
HOOK_DEF(int, symlink, const char *oldpath, const char *newpath) { return new_symlinkat(oldpath, AT_FDCWD, newpath); }

HOOK_DEF(int, chdir, const char *pathname) {
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, true, false);
    RETURN_IF_FORBID
    int ret = syscall(__NR_chdir, redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


// int __statfs (__const char *__file, struct statfs *__buf);
HOOK_DEF(int, __statfs, __const char *__file, struct statfs *__buf) {
    int res;
    const char *redirect_path = shared_relocate(__file, &res, AT_FDCWD, true, false);
    RETURN_IF_FORBID
    int ret = syscall(__NR_statfs, redirect_path, __buf);
    FREE(redirect_path, __file);
    return ret;
}

// int statfs64 (__const char *__file, struct statfs *__buf);
HOOK_DEF(int, statfs64, __const char *__file, struct statfs *__buf) {
    int res;
    const char *redirect_path = shared_relocate(__file, &res, AT_FDCWD, true, false);
    RETURN_IF_FORBID
    int ret = syscall(__NR_statfs, redirect_path, __buf);
    FREE(redirect_path, __file);
    return ret;
}

int inline getArrayItemCount(char *const array[]) {
    int i;
    for (i = 0; array[i]; ++i);
    return i;
}


char **build_new_env(char *const envp[]) {
    char *provided_ld_preload = NULL;
    int provided_ld_preload_index = -1;
    int orig_envp_count = getArrayItemCount(envp);

    for (int i = 0; i < orig_envp_count; i++) {
        if (strstr(envp[i], "LD_PRELOAD")) {
            provided_ld_preload = envp[i];
            provided_ld_preload_index = i;
        }
    }
    char ld_preload[200];
    char *so_path = getenv("V_SO_PATH");
    if (provided_ld_preload) {
        sprintf(ld_preload, "LD_PRELOAD=%s:%s", so_path, provided_ld_preload + 11);
    } else {
        sprintf(ld_preload, "LD_PRELOAD=%s", so_path);
    }
    int virtual_env_count = 0;
    for (int i = 0; environ[i]; ++i) {
        if (environ[i][0] == 'V' && environ[i][1] == '_') {
            virtual_env_count++;
        }
    }
    int new_envp_count = orig_envp_count + virtual_env_count + 2;
    char **new_envp = (char **) malloc(new_envp_count * sizeof(char *));
    int cur = 0;
    new_envp[cur++] = ld_preload;
    for (int i = 0; i < orig_envp_count; ++i) {
        if (i != provided_ld_preload_index) {
            new_envp[cur++] = envp[i];
        }
    }
    for (int i = 0; environ[i]; ++i) {
        if (environ[i][0] == 'V' && environ[i][1] == '_') {
            new_envp[cur++] = environ[i];
        }
    }
    new_envp[cur] = NULL;
    return new_envp;
}

char **build_new_argv(char *const envp[]) {
    char *provided_ld_preload = NULL;
    int provided_ld_preload_index = -1;
    int orig_envp_count = getArrayItemCount(envp);

    for (int i = 0; i < orig_envp_count; i++) {
        if (strstr(envp[i], "compiler-filter")) {
            provided_ld_preload = envp[i];
            provided_ld_preload_index = i;
        }
    }
    char ld_preload[40];
    if (provided_ld_preload) {
        sprintf(ld_preload, "--compiler-filter=%s", "everything");
    }

    char *api_level_char = getenv("V_API_LEVEL");
    int api_level = atoi(api_level_char);

    int new_envp_count = orig_envp_count + 4;
    char **new_envp = (char **) malloc(new_envp_count * sizeof(char *));
    int cur = 0;
    for (int i = 0; i < orig_envp_count; ++i) {
        if (i != provided_ld_preload_index) {
            new_envp[cur++] = envp[i];
        } else {
            new_envp[i] = ld_preload;
            cur++;
        }
    }

    if (api_level >= 22) {
        new_envp[cur++] = (char *) "--compile-pic";
    }
    if (api_level >= 23) {
        new_envp[cur++] = (char *) (api_level > 25 ? "--inline-max-code-units=0" : "--inline-depth-limit=0");
    }
    if (api_level >= 28) {
        new_envp[cur++] = (char *) "--debuggable";
    }
    new_envp[cur] = NULL;

//    int n = getArrayItemCount(new_envp);
//    for (int i = 0; i < n; i++) {
//        ALOGE("dex2oat : %s", new_envp[i]);
//    }

    return new_envp;
}

// int (*origin_execve)(const char *pathname, char *const argv[], char *const envp[]);
HOOK_DEF(int, execve, const char *pathname, char *argv[], char *const envp[]) {
    /**
     * CANNOT LINK EXECUTABLE "/system/bin/cat": "/data/app/io.virtualapp-1/lib/arm/libva-native.so" is 32-bit instead of 64-bit.
     *
     * We will support 64Bit to adopt it.
     */
    // ALOGE("execve : %s", pathname); // any output can break exec. See bug: https://issuetracker.google.com/issues/109448553
    int res;
    const char *redirect_path = shared_relocate(pathname, &res, AT_FDCWD, true, false);
    RETURN_IF_FORBID
    char *ld = getenv("LD_PRELOAD");
    if (ld) {
        if (strstr(ld, "libNimsWrap.so") || strstr(ld, "stamina.so")) {
            int ret = syscall(__NR_execve, redirect_path, argv, envp);
            FREE(redirect_path, pathname);
            return ret;
        }
    }
    if (strstr(pathname, "dex2oat")) {
        char **new_envp = build_new_env(envp);
        char **new_argv = build_new_argv(argv);
        int ret = syscall(__NR_execve, redirect_path, new_argv, new_envp);
        FREE(redirect_path, pathname);
        free(new_envp);
        free(new_argv);
        return ret;
    }
    if (strstr(pathname, "app_process")) {
        char **new_envp = build_new_env(envp);
        int ret = syscall(__NR_execve, redirect_path, argv, new_envp);
        FREE(redirect_path, pathname);
        free(new_envp);
        return ret;
    }
    int ret = syscall(__NR_execve, redirect_path, argv, envp);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(void*, dlopen, const char *filename, int flag) {
    int res;
    const char *redirect_path = relocate_path(filename, &res);
    if (res == FORBID) { errno = EACCES; return nullptr; }
    void *ret = orig_dlopen(redirect_path, flag);
    onSoLoaded(filename, ret);
    ALOGD("dlopen : %s, return : %p.", redirect_path, ret);
    FREE(redirect_path, filename);
    return ret;
}

HOOK_DEF(void*, do_dlopen_V19, const char *filename, int flag, const void *extinfo) {
    int res;
    const char *redirect_path = relocate_path(filename, &res);
    if (res == FORBID) { errno = EACCES; return nullptr; }
    void *ret = orig_do_dlopen_V19(redirect_path, flag, extinfo);
    onSoLoaded(filename, ret);
    ALOGD("do_dlopen : %s, return : %p.", redirect_path, ret);
    FREE(redirect_path, filename);
    return ret;
}

HOOK_DEF(void*, do_dlopen_V24, const char *name, int flags, const void *extinfo,
         void *caller_addr) {
    int res;
    const char *redirect_path = relocate_path(name, &res);
    if (res == FORBID) { errno = EACCES; return nullptr; }
    void *ret = orig_do_dlopen_V24(redirect_path, flags, extinfo, caller_addr);
    onSoLoaded(name, ret);
    ALOGD("do_dlopen : %s, return : %p.", redirect_path, ret);
    FREE(redirect_path, name);
    return ret;
}



//void *dlsym(void *handle,const char *symbol)
HOOK_DEF(void*, dlsym, void *handle, char *symbol) {
    ALOGD("dlsym : %p %s.", handle, symbol);
    return orig_dlsym(handle, symbol);
}

// int kill(pid_t pid, int sig);
HOOK_DEF(int, kill, pid_t pid, int sig) {
    ALOGD(">>>>> kill >>> pid: %d, sig: %d.", pid, sig);
    int ret = syscall(__NR_kill, pid, sig);
    return ret;
}

HOOK_DEF(pid_t, vfork) {
    return fork();
}

__END_DECLS
// end IO DEF

static void ensure_uid_hook() {
    if (uid_hook_installed) {
        return;
    }
    void *handle = dlopen("libc.so", RTLD_NOW);
    if (handle != nullptr) {
        HOOK_SYMBOL(handle, getuid);
        dlclose(handle);
        uid_hook_installed = orig_getuid != nullptr;
    }
}

static bool ensure_shared_media_hooks() {
    ALOGI("SharedMediaHooks phase=activation-enter");
    static std::mutex mutex;
    std::lock_guard<std::mutex> lock(mutex);
    ALOGI("SharedMediaHooks phase=activation-locked");
    void *handle = dlopen("libc.so", RTLD_NOW);
    if (!handle) {
        ALOGI("SharedMediaHooks phase=libc-unavailable ready=0");
        return false;
    }
    HOOK_SYMBOL(handle, opendir);
    HOOK_SYMBOL(handle, access);
    HOOK_SYMBOL(handle, chmod);
    HOOK_SYMBOL(handle, fchmodat);
    HOOK_SYMBOL(handle, open);
    HOOK_SYMBOL(handle, openat);
    HOOK_SYMBOL(handle, __open_2);
    HOOK_SYMBOL(handle, __openat_2);
    HOOK_SYMBOL(handle, fopen);
    HOOK_SYMBOL(handle, mkdir);
    HOOK_SYMBOL(handle, rename);
    HOOK_SYMBOL(handle, unlink);
    HOOK_SYMBOL(handle, rmdir);
    HOOK_SYMBOL(handle, fchownat);
    HOOK_SYMBOL(handle, renameat);
    HOOK_SYMBOL(handle, mkdirat);
    HOOK_SYMBOL(handle, mknodat);
    HOOK_SYMBOL(handle, truncate);
    HOOK_SYMBOL(handle, linkat);
    HOOK_SYMBOL(handle, unlinkat);
    HOOK_SYMBOL(handle, symlinkat);
    HOOK_SYMBOL(handle, utimensat);
    HOOK_SYMBOL(handle, read);
    HOOK_SYMBOL(handle, write);
    HOOK_SYMBOL(handle, pread);
    HOOK_SYMBOL(handle, pwrite);
    HOOK_SYMBOL(handle, pread64);
    HOOK_SYMBOL(handle, pwrite64);
    HOOK_SYMBOL(handle, preadv);
    HOOK_SYMBOL(handle, pwritev);
    HOOK_SYMBOL(handle, readv);
    HOOK_SYMBOL(handle, writev);
    HOOK_SYMBOL(handle, ftruncate);
    HOOK_SYMBOL(handle, ftruncate64);
    HOOK_SYMBOL(handle, fchmod);
    HOOK_SYMBOL(handle, fchown);
    HOOK_SYMBOL(handle, mmap);
    HOOK_SYMBOL(handle, mmap64);
    HOOK_SYMBOL(handle, creat);
    HOOK_SYMBOL(handle, truncate64);
    HOOK_SYMBOL(handle, chown);
    HOOK_SYMBOL(handle, lchown);
    HOOK_SYMBOL(handle, link);
    HOOK_SYMBOL(handle, symlink);
    HOOK_SYMBOL(handle, futimens);
    HOOK_SYMBOL(handle, utime);
    HOOK_SYMBOL(handle, utimes);
    bool ready =
            orig_access != nullptr
            && orig_opendir != nullptr
            && orig_chmod != nullptr
            && orig_fchmodat != nullptr
            && orig_open != nullptr
            && orig_openat != nullptr
            && orig___open_2 != nullptr
            && orig___openat_2 != nullptr
            && orig_fopen != nullptr
            && orig_mkdir != nullptr
            && orig_rename != nullptr
            && orig_unlink != nullptr
            && orig_rmdir != nullptr
            && orig_fchownat != nullptr
            && orig_renameat != nullptr
            && orig_mkdirat != nullptr
            && orig_mknodat != nullptr
            && orig_truncate != nullptr
            && orig_linkat != nullptr
            && orig_unlinkat != nullptr
            && orig_symlinkat != nullptr
            && orig_utimensat != nullptr
            && orig_read != nullptr
            && orig_write != nullptr
            && orig_pread != nullptr
            && orig_pwrite != nullptr
            && orig_pread64 != nullptr
            && orig_pwrite64 != nullptr
            && orig_readv != nullptr
            && orig_preadv != nullptr
            && orig_pwritev != nullptr
            && orig_writev != nullptr
            && orig_ftruncate != nullptr
            && orig_ftruncate64 != nullptr
            && orig_fchmod != nullptr
            && orig_fchown != nullptr
            && orig_mmap != nullptr
            && orig_mmap64 != nullptr
            && orig_creat != nullptr
            && orig_truncate64 != nullptr
            && orig_chown != nullptr
            && orig_lchown != nullptr
            && orig_link != nullptr
            && orig_symlink != nullptr
            && orig_futimens != nullptr
            && orig_utime != nullptr
            && orig_utimes != nullptr;
    dlclose(handle);
    ALOGI("SharedMediaHooks phase=activation-complete ready=%d", ready);
    return ready;
}

bool IOUniformer::sharedMediaHooksReady() {
    return shared_media_policy.empty() || ensure_shared_media_hooks();
}

void IOUniformer::configureUidOverride(int uid_override_value) {
    uid_override.store(uid_override_value);
    if (uid_override_value >= 0) {
        char uid_override_chars[16];
        snprintf(uid_override_chars, sizeof(uid_override_chars), "%d", uid_override_value);
        setenv("V_REPORTED_UID", uid_override_chars, 1);
        ensure_uid_hook();
    } else {
        // Do not allow a previous process claim to leak a native UID override into exec'd children.
        unsetenv("V_REPORTED_UID");
    }
}

int IOUniformer::readUidForProbe() {
    return static_cast<int>(getuid());
}


void onSoLoaded(const char *name, void *handle) {
}

int findSymbol(const char *name, const char *libn,
               unsigned long *addr) {
    return find_name(getpid(), name, libn, addr);
}

void hook_dlopen(int api_level) {
    void *symbol = NULL;
    if (api_level > 25) {
        if (findSymbol("__dl__Z9do_dlopenPKciPK17android_dlextinfoPKv", "linker",
                       (unsigned long *) &symbol) == 0) {
            hook_function(symbol, (void *) new_do_dlopen_V24,
                           (void **) &orig_do_dlopen_V24);
        }
    } else if (api_level > 23) {
        if (findSymbol("__dl__Z9do_dlopenPKciPK17android_dlextinfoPv", "linker",
                       (unsigned long *) &symbol) == 0) {
            hook_function(symbol, (void *) new_do_dlopen_V24,
                          (void **) &orig_do_dlopen_V24);
        }
    } else if (api_level >= 19) {
        if (findSymbol("__dl__Z9do_dlopenPKciPK17android_dlextinfo", "linker",
                       (unsigned long *) &symbol) == 0) {
            hook_function(symbol, (void *) new_do_dlopen_V19,
                          (void **) &orig_do_dlopen_V19);
        }
    } else {
        if (findSymbol("__dl_dlopen", "linker",
                       (unsigned long *) &symbol) == 0) {
            hook_function(symbol, (void *) new_dlopen, (void **) &orig_dlopen);
        }
    }
}


void IOUniformer::startUniformer(const char *so_path, const char *host_package,
                                 const char *guest_process_name, int api_level,
                                 int preview_api_level) {
    std::lock_guard<std::mutex> lock(uniformer_start_mutex);
    char api_level_chars[5];
    setenv("V_SO_PATH", so_path, 1);
    if (host_package != nullptr) {
        proc_maps_host_package.assign(host_package);
        setenv("V_HOST_PACKAGE", host_package, 1);
    }
    if (guest_process_name != nullptr) {
        proc_guest_process_name.assign(guest_process_name);
        setenv("V_GUEST_PROCESS", guest_process_name, 1);
    }
    sprintf(api_level_chars, "%i", api_level);
    setenv("V_API_LEVEL", api_level_chars, 1);
    sprintf(api_level_chars, "%i", preview_api_level);
    setenv("V_PREVIEW_API_LEVEL", api_level_chars, 1);

    // This can run once from the library constructor (using inherited environment
    // values) and again from NativeEngine after the guest binds. Re-hooking an
    // already patched libc symbol may return our replacement as the trampoline;
    // opendir would then recurse into new_opendir until the process stack overflows.
    // Keep identity/environment refreshes above, but install the hooks only once.
    if (uniformer_started) {
        ALOGI("IOUniformer hooks already installed; refreshed guest identity only.");
        return;
    }

    void *handle = dlopen("libc.so", RTLD_NOW);
    if (handle) {
        HOOK_SYMBOL(handle, access);
        HOOK_SYMBOL(handle, chmod);
        HOOK_SYMBOL(handle, fchmodat);
        HOOK_SYMBOL(handle, stat);
        HOOK_SYMBOL(handle, lstat);
        HOOK_SYMBOL(handle, open);
        HOOK_SYMBOL(handle, openat);
        HOOK_SYMBOL(handle, __open_2);
        HOOK_SYMBOL(handle, __openat_2);
        HOOK_SYMBOL(handle, fopen);
        HOOK_SYMBOL(handle, opendir);
        HOOK_SYMBOL(handle, mkdir);
        HOOK_SYMBOL(handle, rename);
        HOOK_SYMBOL(handle, unlink);
        HOOK_SYMBOL(handle, rmdir);
        HOOK_SYMBOL(handle, fchownat);
        HOOK_SYMBOL(handle, renameat);
        HOOK_SYMBOL(handle, fstatat64);
        HOOK_SYMBOL(handle, __statfs);
        HOOK_SYMBOL(handle, mkdirat);
        HOOK_SYMBOL(handle, mknodat);
        HOOK_SYMBOL(handle, truncate);
        HOOK_SYMBOL(handle, linkat);
        HOOK_SYMBOL(handle, readlinkat);
        HOOK_SYMBOL(handle, unlinkat);
        HOOK_SYMBOL(handle, symlinkat);
        HOOK_SYMBOL(handle, utimensat);
        HOOK_SYMBOL(handle, chdir);
        HOOK_SYMBOL(handle, execve);
        HOOK_SYMBOL(handle, statfs64);
        dlclose(handle);
        uniformer_started = true;
    }
    // Runtime.nativeLoad reaches the linker namespace check before libc open
    // hooks. Rewrite its absolute path while retaining the guest class loader.
    void *openjdk_handle = dlopen("libopenjdkjvm.so", RTLD_NOW);
    if (openjdk_handle != nullptr) {
        HOOK_SYMBOL(openjdk_handle, JVM_NativeLoad);
        dlclose(openjdk_handle);
    }
    void *libdl_handle = dlopen("libdl.so", RTLD_NOW);
    if (libdl_handle != nullptr) {
        if (should_hook_android_dlopen_ext(api_level, guest_process_name)) {
            HOOK_SYMBOL(libdl_handle, android_dlopen_ext);
        } else {
            // Facebook installs its own loader hook from memfd:distract. On Android 17 it cannot
            // safely chain over our existing inline hook and corrupts the PAC-aware entry point.
            // Runtime.nativeLoad remains redirected by JVM_NativeLoad above.
            ALOGI("Skipped android_dlopen_ext hook for Facebook Android 17+ loader compatibility.");
        }
        dlclose(libdl_handle);
    }
}

int IOUniformer::countProcMapsLeaksForProbe() {
    int fd = open_sanitized_proc_maps(O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }
    int leaks = 0;
    std::string pending;
    char buffer[4096];
    while (true) {
        ssize_t count = static_cast<ssize_t>(syscall(__NR_read, fd, buffer, sizeof(buffer)));
        if (count == 0) {
            break;
        }
        if (count < 0) {
            syscall(__NR_close, fd);
            return -1;
        }
        pending.append(buffer, static_cast<size_t>(count));
    }
    syscall(__NR_close, fd);
    size_t start = 0;
    while (start < pending.size()) {
        size_t newline = pending.find('\n', start);
        size_t end = newline == std::string::npos ? pending.size() : newline + 1;
        if (is_proc_maps_leak(pending.data() + start, end - start)) {
            ++leaks;
        }
        start = end;
    }
    return leaks;
}
