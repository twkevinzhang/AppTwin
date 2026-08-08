//
// VirtualApp Native Project
//
#include <unistd.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <atomic>
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
#include "SymbolFinder.h"

bool iu_loaded = false;

static std::atomic<int> uid_override(-1);
static bool uid_hook_installed = false;
static std::string proc_maps_host_package;

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
        startUniformer(getenv("V_SO_PATH"), getenv("V_HOST_PACKAGE"), api_level,
                       preview_api_level);
        char *uid_override_chars = getenv("V_REPORTED_UID");
        if (uid_override_chars) {
            configureUidOverride(atoi(uid_override_chars));
        }
        iu_loaded = true;
    }
}

static inline void
hook_function(void *addr, void *new_func, void **old_func) {
#ifdef __aarch64__
    A64HookFunction(addr, new_func, old_func);
#else
    MSHookFunction(addr, new_func, old_func);
#endif

}

static inline void
hook_function(void *handle, const char *symbol, void *new_func, void **old_func) {
    void *addr = dlsym(handle, symbol);
    if (addr == NULL) {
        return;
    }
    hook_function(addr, new_func, old_func);
}


void onSoLoaded(const char *name, void *handle);

void IOUniformer::redirect(const char *orig_path, const char *new_path) {
    add_replace_item(orig_path, new_path);
}

const char *IOUniformer::query(const char *orig_path) {
    return reverse_relocate_path(orig_path);
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
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_faccessat, AT_FDCWD, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, stat, const char *pathname, struct stat *buf) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_newfstatat, AT_FDCWD, redirect_path, buf, 0);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, lstat, const char *pathname, struct stat *buf) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
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
    if ((flags & O_ACCMODE) == O_RDONLY && is_proc_maps_path(pathname)) {
        int sanitized = open_sanitized_proc_maps(flags);
        if (sanitized >= 0) {
            return sanitized;
        }
    }
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
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
    if ((flags & O_ACCMODE) == O_RDONLY && is_proc_maps_path(pathname)) {
        int sanitized = open_sanitized_proc_maps(flags);
        if (sanitized >= 0) {
            return sanitized;
        }
    }
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_openat, dirfd, redirect_path, flags, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(FILE *, fopen, const char *pathname, const char *mode) {
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
    const char *redirect_path = relocate_path(pathname, &res);
    if (res == FORBID) {
        return nullptr;
    }
    FILE *ret = orig_fopen(redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(DIR *, opendir, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    if (res == FORBID) {
        return nullptr;
    }
    DIR *ret = orig_opendir(redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, mkdir, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_mkdirat, AT_FDCWD, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, rename, const char *oldpath, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_renameat, AT_FDCWD, redirect_path_old,
                      AT_FDCWD, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


HOOK_DEF(int, unlink, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_unlinkat, AT_FDCWD, redirect_path, 0);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(int, rmdir, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_unlinkat, AT_FDCWD, redirect_path, AT_REMOVEDIR);
    FREE(redirect_path, pathname);
    return ret;
}



// int fstatat64(int dirfd, const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstatat64, int dirfd, const char *pathname, struct stat *buf, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_newfstatat, dirfd, redirect_path, buf, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev);
HOOK_DEF(int, mknodat, int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_mknodat, dirfd, redirect_path, mode, dev);
    FREE(redirect_path, pathname);
    return ret;
}


// int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags);
HOOK_DEF(int, utimensat, int dirfd, const char *pathname, const struct timespec times[2],
         int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_utimensat, dirfd, redirect_path, times, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags);
HOOK_DEF(int, fchownat, int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_fchownat, dirfd, redirect_path, owner, group, flags);
    FREE(redirect_path, pathname);
    return ret;
}

// int chroot(const char *pathname);
HOOK_DEF(int, chroot, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_chroot, redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


// int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, renameat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_renameat, olddirfd, redirect_path_old, newdirfd, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


// int unlinkat(int dirfd, const char *pathname, int flags);
HOOK_DEF(int, unlinkat, int dirfd, const char *pathname, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_unlinkat, dirfd, redirect_path, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int symlinkat(const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, symlinkat, const char *oldpath, int newdirfd, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_symlinkat, redirect_path_old, newdirfd, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}

// int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags);
HOOK_DEF(int, linkat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
         int flags) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_linkat, olddirfd, redirect_path_old, newdirfd, redirect_path_new, flags);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


// int mkdirat(int dirfd, const char *pathname, mode_t mode);
HOOK_DEF(int, mkdirat, int dirfd, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_mkdirat, dirfd, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


// int readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
HOOK_DEF(int, readlinkat, int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_readlinkat, dirfd, redirect_path, buf, bufsiz);
    FREE(redirect_path, pathname);
    return ret;
}

// int truncate(const char *path, off_t length);
HOOK_DEF(int, truncate, const char *pathname, off_t length) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_truncate, redirect_path, length);
    FREE(redirect_path, pathname);
    return ret;
}

// int chdir(const char *path);
HOOK_DEF(int, chdir, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_chdir, redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


// int __statfs (__const char *__file, struct statfs *__buf);
HOOK_DEF(int, __statfs, __const char *__file, struct statfs *__buf) {
    int res;
    const char *redirect_path = relocate_path(__file, &res);
    int ret = syscall(__NR_statfs, redirect_path, __buf);
    FREE(redirect_path, __file);
    return ret;
}

// int statfs64 (__const char *__file, struct statfs *__buf);
HOOK_DEF(int, statfs64, __const char *__file, struct statfs *__buf) {
    int res;
    const char *redirect_path = relocate_path(__file, &res);
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
    const char *redirect_path = relocate_path(pathname, &res);
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
    void *ret = orig_dlopen(redirect_path, flag);
    onSoLoaded(filename, ret);
    ALOGD("dlopen : %s, return : %p.", redirect_path, ret);
    FREE(redirect_path, filename);
    return ret;
}

HOOK_DEF(void*, do_dlopen_V19, const char *filename, int flag, const void *extinfo) {
    int res;
    const char *redirect_path = relocate_path(filename, &res);
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

void IOUniformer::configureUidOverride(int uid_override_value) {
    uid_override.store(uid_override_value);
    if (uid_override_value >= 0) {
        char uid_override_chars[16];
        snprintf(uid_override_chars, sizeof(uid_override_chars), "%d", uid_override_value);
        setenv("V_REPORTED_UID", uid_override_chars, 1);
        ensure_uid_hook();
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


void IOUniformer::startUniformer(const char *so_path, const char *host_package, int api_level,
                                 int preview_api_level) {
    char api_level_chars[5];
    setenv("V_SO_PATH", so_path, 1);
    if (host_package != nullptr) {
        proc_maps_host_package.assign(host_package);
        setenv("V_HOST_PACKAGE", host_package, 1);
    }
    sprintf(api_level_chars, "%i", api_level);
    setenv("V_API_LEVEL", api_level_chars, 1);
    sprintf(api_level_chars, "%i", preview_api_level);
    setenv("V_PREVIEW_API_LEVEL", api_level_chars, 1);

    void *handle = dlopen("libc.so", RTLD_NOW);
    if (handle) {
        HOOK_SYMBOL(handle, access);
        HOOK_SYMBOL(handle, stat);
        HOOK_SYMBOL(handle, lstat);
        HOOK_SYMBOL(handle, open);
        HOOK_SYMBOL(handle, openat);
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
    }
    // hook_dlopen(api_level);
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
