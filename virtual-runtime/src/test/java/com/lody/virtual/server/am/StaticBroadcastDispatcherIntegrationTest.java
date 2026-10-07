package com.lody.virtual.server.am;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StaticBroadcastDispatcherIntegrationTest {
    @Test
    public void lateUserRemovalBroadcastCannotStartOrDispatchToDeletedUser() throws Exception {
        String source = read("src/main/java/com/lody/virtual/server/am/VActivityManagerService.java");
        int start = source.indexOf("private ProcessRecord startProcessWithGateHeld(");
        int firstCheck = source.indexOf("isRuntimeAvailable(userId)", start);
        int query = source.indexOf("getApplicationInfo(packageName, 0, userId)", start);
        int catchRace = source.indexOf("catch (SecurityException staleUser)", query);
        assertTrue(firstCheck > start && firstCheck < query);
        assertTrue(catchRace > query);
        assertTrue(source.indexOf("isRuntimeAvailable(userId)", catchRace) > catchRace);
        int dispatch = source.indexOf("boolean dispatchStaticBroadcastWithAcquiredGate(");
        assertTrue(source.indexOf("isRuntimeAvailable(userId)", dispatch)
                < source.indexOf("startProcessIfNeedLocked(info.processName", dispatch));
        int enqueue = source.indexOf("mStaticBroadcastDispatcher.enqueue(", dispatch);
        assertTrue(source.indexOf("isRuntimeAvailable(userId)", enqueue) > enqueue);
        String users = read("src/main/java/com/lody/virtual/server/pm/VUserManagerService.java");
        assertTrue(users.contains("user != null && !user.partial && !mRemovingUserIds.contains(userId)"));
    }

    @Test
    public void guestDispatchAndAckAreOnewayAndGenerationBound() throws Exception {
        String clientAidl = read("src/main/aidl/com/lody/virtual/client/IVClient.aidl");
        String managerAidl = read("src/main/aidl/com/lody/virtual/server/IActivityManager.aidl");

        assertTrue(clientAidl.contains("oneway void scheduleReceiver"));
        assertTrue(clientAidl.contains("long dispatchToken"));
        assertTrue(clientAidl.contains("long processGeneration"));
        assertTrue(managerAidl.contains("oneway void broadcastFinish(long dispatchToken"));
    }

    @Test
    public void vamsDoesNotSynchronouslyCallStaticReceiver() throws Exception {
        String source = read("src/main/java/com/lody/virtual/server/am/VActivityManagerService.java");
        int dispatch = source.indexOf("boolean dispatchStaticBroadcastWithAcquiredGate(");
        int next = source.indexOf("synchronized LinePushStopFence.StopScope", dispatch);
        String body = source.substring(dispatch, next);

        assertTrue(body.contains("mStaticBroadcastDispatcher.enqueue"));
        assertFalse(body.contains("client.scheduleReceiver"));
        assertFalse(body.contains("mLinePushProcessGuard.protectAndDispatch"));
    }

    @Test
    public void clientWaitsForActualPendingResultFinishIncludingGoAsync() throws Exception {
        String source = read("src/main/java/com/lody/virtual/client/VClientImpl.java");
        int receiver = source.indexOf("private void handleReceiver(ReceiverData data)");
        int next = source.indexOf("public IBinder createProxyService", receiver);
        String body = source.substring(receiver, next);

        assertTrue(body.contains("getPendingResult.call(receiver) != null"));
        assertTrue(body.contains("result.finish()"));
        assertFalse(body.contains("VActivityManager.get().broadcastFinish(data.resultData)"));
        assertTrue(source.contains("finishReceiverIfOwned"));
    }

    @Test
    public void dispatcherHasRequestedHardBoundsAndNoActionSpecificLane() throws Exception {
        String source = read("src/main/java/com/lody/virtual/server/am/StaticBroadcastDispatcher.java");
        assertTrue(source.contains("PER_PROCESS_LIMIT = 32"));
        assertTrue(source.contains("GLOBAL_LIMIT = 256"));
        assertTrue(source.contains("HARD_DEADLINE_MILLIS"));
        assertFalse(source.contains("criticalLane"));
        assertFalse(source.contains("dedupe"));
    }

    @Test
    public void ackUsesOpaqueCapabilityRatherThanOnewayCallingPid() throws Exception {
        String vams = read("src/main/java/com/lody/virtual/server/am/VActivityManagerService.java");
        int start = vams.indexOf("public void broadcastFinish(long dispatchToken");
        int end = vams.indexOf("public void notifyBadgerChange", start);
        String ack = vams.substring(start, end);
        String dispatcher = read(
                "src/main/java/com/lody/virtual/server/am/StaticBroadcastDispatcher.java");

        assertFalse(ack.contains("getCallingPid"));
        assertFalse(ack.contains("mPidsSelfLocked"));
        assertTrue(ack.contains(
                "mStaticBroadcastDispatcher.complete(processGeneration, dispatchToken, res)"));
        assertTrue(dispatcher.contains("updated.mToken"));
        assertTrue(dispatcher.contains("completionOwners.take("));
        assertTrue(dispatcher.contains("completionOwners.revoke("));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
