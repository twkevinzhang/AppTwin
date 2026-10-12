#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <string>
#include <android/log.h>

static jboolean fail_stage(const char* stage, int error) {
    __android_log_print(ANDROID_LOG_INFO, "SharedWriteNativeProbe", "stage=%s errno=%d", stage, error);
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_apptwin_fixture_SharedWriteNativeProbe_writeRelative(JNIEnv* env, jclass,
                                                            jstring owned_path, jbyteArray bytes) {
    if (owned_path == nullptr || bytes == nullptr) return fail_stage("input-null", 0);
    const jsize length = env->GetArrayLength(bytes);
    if (length <= 0 || length > 4096) return fail_stage("input-length", 0);
    const char* raw = env->GetStringUTFChars(owned_path, nullptr);
    if (raw == nullptr) return fail_stage("input-string", 0);
    const std::string path(raw);
    env->ReleaseStringUTFChars(owned_path, raw);
    const size_t separator = path.rfind('/');
    if (separator == std::string::npos || separator == 0) return fail_stage("input-separator", 0);
    const std::string parent = path.substr(0, separator);
    const std::string name = path.substr(separator + 1);
    if (name.rfind("apptwin-write-", 0) != 0 || name.size() < 4 ||
        name.compare(name.size() - 4, 4, ".png") != 0 || name.find("..") != std::string::npos ||
        parent.size() < 9 || parent.compare(parent.size() - 9, 9, "/Pictures") != 0) return fail_stage("input-owned-boundary", 0);
    jbyte* content = env->GetByteArrayElements(bytes, nullptr);
    if (content == nullptr) return fail_stage("input-bytes", 0);
    const int directory = open(parent.c_str(), O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) {
        const int error = errno;
        env->ReleaseByteArrayElements(bytes, content, JNI_ABORT);
        return fail_stage("parent-open", error);
    }
    const int file = openat(directory, name.c_str(), O_WRONLY | O_TRUNC | O_CLOEXEC);
    bool success = file >= 0;
    const char* failed_stage = file < 0 ? "relative-open" : nullptr;
    int failed_errno = file < 0 ? errno : 0;
    if (file >= 0) {
        size_t offset = 0;
        while (offset < static_cast<size_t>(length)) {
            const ssize_t written = write(file, content + offset, static_cast<size_t>(length) - offset);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) {
                failed_stage = "write"; failed_errno = written < 0 ? errno : 0;
                success = false; break;
            }
            offset += static_cast<size_t>(written);
        }
        if (close(file) != 0) {
            if (success) { failed_stage = "file-close"; failed_errno = errno; }
            success = false;
        }
    }
    if (close(directory) != 0) {
        if (success) { failed_stage = "parent-close"; failed_errno = errno; }
        success = false;
    }
    env->ReleaseByteArrayElements(bytes, content, JNI_ABORT);
    if (!success) return fail_stage(failed_stage, failed_errno);
    __android_log_print(ANDROID_LOG_INFO, "SharedWriteNativeProbe", "stage=complete errno=0");
    return JNI_TRUE;
}
