#ifndef APPTWIN_SHARED_MEDIA_POLICY_H
#define APPTWIN_SHARED_MEDIA_POLICY_H
#include <string>
#include <vector>
#include <mutex>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <cerrno>
#include <cstdio>

// Resolve with raw readlinkat: libc realpath can recurse through our stat hooks.
namespace SharedMedia {
inline ssize_t rawReadlink(const char *path, char *buffer, size_t size) {
#ifdef __linux__
    return syscall(__NR_readlinkat, AT_FDCWD, path, buffer, size);
#else
    return ::readlinkat(AT_FDCWD, path, buffer, size);
#endif
}

inline std::string resolve(const std::string &input, int dirfd = AT_FDCWD, bool followLinks = true) {
    if (input.empty()) return {};
    std::string pending = input;
    if (pending[0] != '/') {
        char base[4096];
        if (dirfd == AT_FDCWD) {
            if (!getcwd(base, sizeof(base))) return {};
        } else {
            #ifdef __linux__
            char name[64]; snprintf(name, sizeof(name), "/proc/self/fd/%d", dirfd);
            ssize_t size = rawReadlink(name, base, sizeof(base)-1);
            if (size <= 0) return {};
            base[size] = 0;
#else
            if (fcntl(dirfd, F_GETPATH, base) == -1) return {};
#endif
        }
        pending = std::string(base) + "/" + pending;
    }
    std::string current;
    int links = 0;
    while (!pending.empty()) {
        size_t slash = pending.find('/');
        std::string part = pending.substr(0, slash);
        pending = slash == std::string::npos ? "" : pending.substr(slash+1);
        if (part.empty() || part == ".") continue;
        if (part == "..") {
            size_t at = current.rfind('/'); current = at == std::string::npos ? "" : current.substr(0, at);
            continue;
        }
        std::string next = current + "/" + part;
        char target[4096];
        ssize_t length = followLinks ? rawReadlink(next.c_str(), target, sizeof(target)-1) : -1;
        if (!followLinks) errno = EINVAL;
        if (length >= 0) {
            if (++links > 40 || length >= static_cast<ssize_t>(sizeof(target)-1)) return {};
            target[length] = 0;
            std::string link(target);
            if (!link.empty() && link[0] == '/') current.clear();
            pending = link + (pending.empty() ? "" : "/" + pending);
        } else if (errno == EINVAL || errno == ENOENT || errno == ENOTDIR) {
            current = next;
        } else return {};
    }
    return current.empty() ? "/" : current;
}
inline bool within(const std::string &path, const std::string &root) {
    return path == root || (path.size() > root.size() && path.compare(0, root.size(), root) == 0 && path[root.size()] == '/');
}
inline bool openReads(int flags) {
#ifdef O_PATH
    if (flags & O_PATH) return false;
#endif
    return (flags & O_ACCMODE) != O_WRONLY;
}
inline bool openWrites(int flags) {
    return (flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_TRUNC | O_APPEND)) != 0;
}
struct Rule { std::string root; std::string alias; bool read; bool write; };
class Policy {
    std::vector<Rule> rules;
    mutable std::mutex mutex;
public:
    bool empty() const { std::lock_guard<std::mutex> lock(mutex); return rules.empty(); }
    bool add(const char *directory, bool read, bool write) {
        if (!directory || directory[0] != '/') return false;
        std::string root = resolve(directory);
        if (root.empty() || root == "/") return false;
        std::lock_guard<std::mutex> lock(mutex);
        for (auto &rule : rules) if (rule.root == root) { rule.read = read; rule.write = write; return true; }
        rules.push_back({root, resolve(directory, AT_FDCWD, false), read, write}); return true;
    }
    bool protects(const char *path, int dirfd = AT_FDCWD) const {
        if (!path) return false;
        std::lock_guard<std::mutex> lock(mutex);
        std::string resolved = resolve(path, dirfd);
        std::string lexical = resolve(path, dirfd, false);
        for (const auto &rule : rules) {
            if (within(resolved, rule.root) || within(lexical, rule.root)
                || within(lexical, rule.alias)) return true;
        }
        return false;
    }
    bool allows(const char *path, int dirfd, bool read, bool write) const {
        std::lock_guard<std::mutex> lock(mutex);
        if (rules.empty() || !path) return true;
        std::string resolved = resolve(path, dirfd);
        std::string lexical = resolve(path, dirfd, false);
        for (const auto &rule : rules) {
            bool protectedPath = within(resolved, rule.root) || within(lexical, rule.root)
                                 || within(lexical, rule.alias);
            if (protectedPath && (resolved.empty() || (read && !rule.read) || (write && !rule.write))) return false;
        }
        return true;
    }
};
}
#endif
