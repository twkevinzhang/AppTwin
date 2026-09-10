package com.lody.virtual.client;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActivityBootstrapRaceSourceTest {
    @Test
    public void processTokenAlwaysComesFromAuthoritativeOwnerSnapshot() throws Exception {
        String client = read("src/main/java/com/lody/virtual/client/VClientImpl.java");

        assertFalse(client.contains("private IBinder token;"));
        assertTrue(client.contains("StubProcessOwner.Identity owner = mProcessOwner.snapshot()"));
        assertTrue(client.contains("synchronized (mProcessOwner)"));
        assertTrue(client.contains("IBinder processToken = getToken()"));
        assertTrue(client.contains("appDoneExecuting(processToken, false)"));
        assertTrue(client.contains("appDoneExecuting(processToken, true)"));
        assertTrue(client.contains("owner.getGeneration() == processGeneration"));
    }

    @Test
    public void missingOwnerNeverRecursesOrQueuesTheLaunchTransaction() throws Exception {
        String transaction = read("src/main/java/android/app/TransactionHandlerProxy.java");
        String legacy = read(
                "src/main/java/com/lody/virtual/client/hook/proxies/am/HCallbackStub.java");

        assertTrue(transaction.contains("awaitActivityProcessOwner("));
        assertTrue(transaction.contains("return LaunchPreparation.ABORT"));
        assertFalse(transaction.contains("return handleLaunchActivity("));
        assertTrue(legacy.contains("awaitActivityProcessOwner("));
        assertTrue(legacy.contains("return HCallbackLaunchHandling.ABORT_CONSUMED"));
        assertFalse(legacy.contains("sendMessageAtFrontOfQueue"));
    }

    @Test
    public void bootstrapWaitUsesFixedDeadlineConditionWithoutPollingSleep() throws Exception {
        String owner = read("src/main/java/com/lody/virtual/client/StubProcessOwner.java");
        String client = read("src/main/java/com/lody/virtual/client/VClientImpl.java");

        assertTrue(owner.contains("Identity awaitIdentity(long timeoutMillis)"));
        assertTrue(owner.contains("deadlineNanos = System.nanoTime() + timeoutNanos"));
        assertTrue(owner.contains("wait(waitMillis, waitNanos)"));
        assertFalse(owner.contains("Thread.sleep"));
        assertTrue(client.contains("PROCESS_RESTART_EXACT_BOOTSTRAP_PENDING"));
        assertTrue(client.contains("mProcessOwner.awaitIdentity("));
        assertTrue(client.contains("Process.killProcess(Process.myPid())"));
    }

    @Test
    public void serverClassifiesOnlyRawExactStubWithActiveSlotReservation() throws Exception {
        String aidl = read("src/main/aidl/com/lody/virtual/server/IActivityManager.aidl");
        String service = read(
                "src/main/java/com/lody/virtual/server/am/VActivityManagerService.java");
        String registry = read(
                "src/main/java/com/lody/virtual/server/am/LogicalProcessOwnerRegistry.java");
        String raw = read(
                "src/main/java/com/lody/virtual/server/am/RawSystemProcessAuthority.java");

        assertTrue(aidl.contains("PROCESS_RESTART_OWNER_READY"));
        assertTrue(aidl.contains("PROCESS_RESTART_EXACT_BOOTSTRAP_PENDING"));
        assertTrue(aidl.contains("int processRestarted"));
        assertTrue(service.contains("Binder.getCallingPid()"));
        assertTrue(service.contains("findExactHostStubSlot("));
        assertTrue(service.contains("findReservationBySlot(slot)"));
        assertTrue(service.contains("setting.isInstalled(userId)"));
        assertTrue(registry.contains("Reservation findReservationBySlot(int slot)"));
        assertTrue(raw.contains("process.uid != hostUid"));
        assertTrue(raw.contains("!Character.isDigit(suffix.charAt(i))"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
