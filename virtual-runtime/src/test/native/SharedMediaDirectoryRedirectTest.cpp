#include "../../main/jni/Foundation/SandboxFs.h"
#include "../../main/jni/Foundation/SharedMediaPolicy.h"
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <sys/stat.h>
// Exercise production keep-directory matching with actual owned files and fd anchors.
int main() {
    char temporary[] = "/tmp/apptwin-native-directory-XXXXXX";
    char *root = mkdtemp(temporary); assert(root);
    const std::string canonicalRoot = SharedMedia::resolve(root);
    std::string shared = canonicalRoot + "/Pictures";
    std::string isolated = canonicalRoot + "/isolated";
    assert(mkdir(shared.c_str(), 0700) == 0);
    assert(mkdir(isolated.c_str(), 0700) == 0);
    const std::string owned = shared + "/owned.bin";
    int file = open(owned.c_str(), O_CREAT | O_WRONLY, 0600); assert(file >= 0);
    assert(write(file, "owned", 5) == 5); close(file);
    const std::string white = shared + "/";
    add_keep_item(white.c_str());
    add_replace_item((canonicalRoot + "/").c_str(), (isolated + "/").c_str());
    assert(get_keep_items()[0].is_folder);
    int result = NOT_MATCH;
    const char *parent = relocate_path(shared.c_str(), &result);
    assert(result == KEEP && strcmp(parent, shared.c_str()) == 0);
    const char *child = relocate_path(owned.c_str(), &result);
    assert(result == KEEP && strcmp(child, owned.c_str()) == 0);
    std::string sibling = shared + "-other/owned.bin";
    const char *other = relocate_path(sibling.c_str(), &result);
    assert(result == MATCH && strcmp(other, sibling.c_str()) != 0); free((void *)other);
#ifdef O_PATH
    int directory = open(parent, O_PATH | O_DIRECTORY);
#else
    int directory = open(parent, O_RDONLY | O_DIRECTORY);
#endif
    assert(directory >= 0);
    std::string absolute = SharedMedia::resolve("owned.bin", directory, false);
    assert(SharedMedia::resolve(absolute) == SharedMedia::resolve(owned));
    const char *relativeTarget = relocate_path(absolute.c_str(), &result);
    assert(result == KEEP);
    SharedMedia::Policy policy;
    assert(policy.add(white.c_str(), true, false));
    assert(!policy.allows(relativeTarget, AT_FDCWD, false, true));
    assert(policy.allows(relativeTarget, AT_FDCWD, true, false));
    assert(policy.add(white.c_str(), false, true));
    assert(!policy.allows(relativeTarget, AT_FDCWD, true, false));
    assert(policy.allows(relativeTarget, AT_FDCWD, false, true));
    file = openat(directory, "owned.bin", O_WRONLY | O_TRUNC); assert(file >= 0);
    assert(write(file, "write", 5) == 5); close(file);
    assert(policy.add(white.c_str(), false, false));
    assert(!policy.allows(relativeTarget, AT_FDCWD, false, true));
    assert(!policy.allows(relativeTarget, AT_FDCWD, true, false));
    close(directory); unlink(owned.c_str()); rmdir(shared.c_str()); rmdir(isolated.c_str()); rmdir(root);
}
