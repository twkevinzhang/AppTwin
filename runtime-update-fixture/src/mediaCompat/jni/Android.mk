LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := apptwin_shared_write_probe
LOCAL_SRC_FILES := SharedWriteNativeProbe.cpp
LOCAL_LDLIBS := -llog
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
