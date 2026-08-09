package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class RawSystemProcessAuthorityTest {
    @Test
    public void exactSystemPidUidAndNameAreRequired() {
        ActivityManager.RunningAppProcessInfo main = process(41, 10_321, "org.apptwin");
        ActivityManager.RunningAppProcessInfo guest = process(42, 10_321, "org.apptwin:p0");

        assertTrue(RawSystemProcessAuthority.containsExactProcess(
                Arrays.asList(main, guest), 41, 10_321, "org.apptwin"));
        assertFalse(RawSystemProcessAuthority.containsExactProcess(
                Arrays.asList(main, guest), 42, 10_321, "org.apptwin"));
        assertFalse(RawSystemProcessAuthority.containsExactProcess(
                Arrays.asList(main, guest), 41, 10_322, "org.apptwin"));
        assertFalse(RawSystemProcessAuthority.containsExactProcess(
                Arrays.asList(main, guest), 41, 10_321, "org.apptwin:p0"));
    }

    @Test
    public void guestMutableArgvCannotReplaceSystemRecordedIdentity() {
        ActivityManager.RunningAppProcessInfo systemRecord =
                process(42, 10_321, "org.apptwin:p0");
        String guestSpoofedArgv = "org.apptwin";

        assertTrue("precondition: argv can imitate the host", "org.apptwin".equals(guestSpoofedArgv));
        assertFalse(RawSystemProcessAuthority.containsExactProcess(
                Collections.singletonList(systemRecord), 42, 10_321, guestSpoofedArgv));
    }

    @Test
    public void unavailableOrMalformedSystemStateFailsClosed() {
        assertFalse(RawSystemProcessAuthority.containsExactProcess(
                null, 41, 10_321, "org.apptwin"));
        assertFalse(RawSystemProcessAuthority.containsExactProcess(
                Collections.singletonList(null), 41, 10_321, "org.apptwin"));
        assertFalse(RawSystemProcessAuthority.containsExactProcess(
                Collections.singletonList(process(41, 10_321, "org.apptwin")),
                -1, 10_321, "org.apptwin"));
    }

    private static ActivityManager.RunningAppProcessInfo process(int pid, int uid, String name) {
        ActivityManager.RunningAppProcessInfo result =
                new ActivityManager.RunningAppProcessInfo();
        result.pid = pid;
        result.uid = uid;
        result.processName = name;
        return result;
    }
}
