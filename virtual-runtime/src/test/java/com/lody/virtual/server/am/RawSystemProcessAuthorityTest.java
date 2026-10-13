package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
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

    @Test
    public void guestProjectionCannotMutateDetachedRawAuthorityIdentity() {
        ActivityManager.RunningAppProcessInfo system = process(42, 10_321, "org.apptwin:p2");
        system.pkgList = new String[]{"org.apptwin"};
        java.util.List<ActivityManager.RunningAppProcessInfo> raw =
                RawSystemProcessAuthority.copyIdentitySnapshot(Collections.singletonList(system));
        java.util.List<ActivityManager.RunningAppProcessInfo> projected =
                RawSystemProcessAuthority.copyIdentitySnapshot(raw);
        assertNotSame(raw.get(0), projected.get(0));
        assertNotSame(raw.get(0).pkgList, projected.get(0).pkgList);
        projected.get(0).processName = "org.apptwin.fixture";
        projected.get(0).uid = 12_345;
        projected.get(0).pkgList[0] = "org.apptwin.fixture";
        system.processName = "tampered-source";
        assertEquals(2, RawSystemProcessAuthority.findExactHostStubSlot(raw, 42, 10_321, "org.apptwin", 50));
        assertEquals(-1, RawSystemProcessAuthority.findExactHostStubSlot(projected, 42, 10_321, "org.apptwin", 50));
        assertEquals("org.apptwin", raw.get(0).pkgList[0]);
    }

    @Test
    public void rawStubAuthorityRequiresExactPidUidPrefixAndBoundedNumericSlot() {
        java.util.List<ActivityManager.RunningAppProcessInfo> raw =
                Collections.singletonList(process(42, 10_321, "org.apptwin:p2"));
        assertEquals(2, RawSystemProcessAuthority.findExactHostStubSlot(raw, 42, 10_321, "org.apptwin", 50));
        assertEquals(-1, RawSystemProcessAuthority.findExactHostStubSlot(raw, 43, 10_321, "org.apptwin", 50));
        assertEquals(-1, RawSystemProcessAuthority.findExactHostStubSlot(raw, 42, 10_322, "org.apptwin", 50));
        for (String name : new String[]{"org.apptwin", "other.host:p2", "org.apptwin:p", "org.apptwin:p-1",
                "org.apptwin:p2suffix", "org.apptwin:p50", "org.apptwin:p999999999999999999999"}) {
            assertEquals(-1, RawSystemProcessAuthority.findExactHostStubSlot(
                    Collections.singletonList(process(42, 10_321, name)), 42, 10_321, "org.apptwin", 50));
        }
        assertNull(RawSystemProcessAuthority.copyIdentitySnapshot(null));
        assertEquals(-1, RawSystemProcessAuthority.findExactHostStubSlot(null, 42, 10_321, "org.apptwin", 50));
        assertEquals(-1, RawSystemProcessAuthority.findExactHostStubSlot(Collections.singletonList(null),
                42, 10_321, "org.apptwin", 50));
    }

    @Test
    public void everyServerAuthorityConsumerUsesCapturedRawProcessSnapshot() throws Exception {
        String service = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/lody/virtual/server/am/VActivityManagerService.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(service.contains("am.getRunningAppProcesses()"));
        for (String method : new String[]{"private String getProcessName(int pid)",
                "private void reconcileRunningStubProcessesLocked()", "private boolean isExpectedRunningStub(int pid, int vpid)"}) {
            int start = service.indexOf(method);
            int end = service.indexOf("\n    }", start);
            assertTrue(start >= 0 && end > start);
            String body = service.substring(start, end);
            assertTrue(body.contains("RawSystemProcessAuthority.rawProcessSnapshot()"));
            assertTrue(body.contains("if (processes == null)"));
        }
        String core = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/lody/virtual/client/core/VirtualCore.java")), java.nio.charset.StandardCharsets.UTF_8);
        int capture = core.indexOf("RawSystemProcessAuthority.captureBeforeHooks()");
        int initialize = core.indexOf("invocationStubManager.init()");
        int inject = core.indexOf("invocationStubManager.injectAll()");
        assertTrue(capture >= 0 && initialize > capture && inject > initialize);
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
