#include "../../main/jni/Foundation/SharedMediaPolicy.h"
#include <cassert>
#include <cstdlib>
#include <sys/stat.h>
#include <cstring>
// All paths and bytes belong to this test; no user media is inspected.
int main() {
    char temporary[] = "/tmp/apptwin-native-media-XXXXXX";
    char *root = mkdtemp(temporary); assert(root);
    std::string shared = std::string(root) + "/shared";
    std::string privateDir = std::string(root) + "/private";
    assert(mkdir(shared.c_str(), 0700) == 0);
    assert(mkdir(privateDir.c_str(), 0700) == 0);
    std::string owned = shared + "/owned.bin";
    int fd = open(owned.c_str(), O_CREAT | O_RDWR, 0600); assert(fd >= 0);
    assert(write(fd, "owned", 5) == 5); close(fd);
    std::string alias = std::string(root) + "/alias";
    assert(symlink(shared.c_str(), alias.c_str()) == 0);
    SharedMedia::Policy policy;
    assert(!policy.add("/", true, true));
    assert(policy.add(shared.c_str(), true, false));
    assert(policy.allows(owned.c_str(), AT_FDCWD, true, false));
    assert(!policy.allows(owned.c_str(), AT_FDCWD, false, true));
    assert(!policy.allows((alias + "/owned.bin").c_str(), AT_FDCWD, false, true));
    assert(!policy.allows((privateDir + "/../shared/new.bin").c_str(), AT_FDCWD, false, true));
    assert(policy.allows((shared + "-sibling/file").c_str(), AT_FDCWD, true, true));
    int dir = open(shared.c_str(), O_RDONLY | O_DIRECTORY); assert(dir >= 0);
    assert(!policy.allows("owned.bin", dir, false, true));
    assert(policy.allows("owned.bin", dir, true, false));
    assert(policy.allows((privateDir + "/private.bin").c_str(), AT_FDCWD, true, true));
    assert(!SharedMedia::openWrites(O_RDONLY));
    assert(SharedMedia::openWrites(O_RDONLY | O_TRUNC));
    assert(SharedMedia::openWrites(O_WRONLY));
    assert(!SharedMedia::openReads(O_WRONLY));
    assert(SharedMedia::openReads(O_RDWR));
    // Gate actual filesystem mutations through the same policy used by hooks.
    auto guardedOpen = [&](int flags) {
        if (!policy.allows(owned.c_str(), AT_FDCWD, SharedMedia::openReads(flags), SharedMedia::openWrites(flags))) {
            errno = EACCES; return -1;
        }
        return open(owned.c_str(), flags, 0600);
    };
    errno = 0; assert(guardedOpen(O_WRONLY | O_TRUNC) == -1 && errno == EACCES);
    fd = guardedOpen(O_RDONLY); assert(fd >= 0); char bytes[5]; assert(read(fd, bytes, sizeof(bytes)) == 5); assert(memcmp(bytes, "owned", 5) == 0); close(fd);
    assert(policy.add(shared.c_str(), false, true));
    errno = 0; assert(guardedOpen(O_RDONLY) == -1 && errno == EACCES);
    fd = guardedOpen(O_WRONLY); assert(fd >= 0); assert(write(fd, "test!", 5) == 5); close(fd);
    assert(policy.add(shared.c_str(), false, false));
    assert(!policy.allows(owned.c_str(), AT_FDCWD, true, false));
    assert(!policy.allows(owned.c_str(), AT_FDCWD, false, true));
    close(dir); unlink(alias.c_str()); unlink(owned.c_str()); rmdir(shared.c_str()); rmdir(privateDir.c_str()); rmdir(root);
}
