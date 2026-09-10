package com.lody.virtual.client;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class FacebookNativeLoaderHookPolicySourceTest {
    @Test
    public void androidDlopenExceptionIsBoundedToFacebookOnAndroid17() throws Exception {
        String uniformer = read("src/main/jni/Foundation/IOUniformer.cpp");
        String policy = between(
                uniformer,
                "static bool should_hook_android_dlopen_ext(",
                "void onSoLoaded(");

        assertTrue(policy.contains("ANDROID_17_API_LEVEL = 37"));
        assertTrue(policy.contains("FACEBOOK_PACKAGE[] = \"com.facebook.katana\""));
        assertTrue(policy.contains("guest_process_name[package_length] != ':'"));
        assertTrue(policy.contains("return true;"));
    }

    @Test
    public void jvmNativeLoadHookRemainsOutsideTheFacebookException() throws Exception {
        String uniformer = read("src/main/jni/Foundation/IOUniformer.cpp");
        String setup = between(
                uniformer,
                "void IOUniformer::startUniformer(",
                "int IOUniformer::countProcMapsLeaksForProbe(");

        int jvmHook = setup.indexOf("HOOK_SYMBOL(openjdk_handle, JVM_NativeLoad);");
        int policyGate = setup.indexOf("should_hook_android_dlopen_ext(api_level, guest_process_name)");
        assertTrue(jvmHook >= 0);
        assertTrue(policyGate > jvmHook);
        assertTrue(setup.contains("HOOK_SYMBOL(libdl_handle, android_dlopen_ext);"));
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex);
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex);
        return value.substring(startIndex, endIndex);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
