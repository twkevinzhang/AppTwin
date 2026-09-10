package com.lody.virtual.server.am;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuestIpcFreezeSafetySourceTest {
    @Test
    public void voidServerToGuestCallsAreOnewayAndGenerationFenced() throws Exception {
        String aidl = read("src/main/aidl/com/lody/virtual/client/IVClient.aidl");
        String client = read("src/main/java/com/lody/virtual/client/VClientImpl.java");

        assertTrue(aidl.contains("oneway void scheduleNewIntent"));
        assertTrue(aidl.contains("oneway void finishActivity"));
        assertTrue(aidl.contains("oneway void scheduleCreateService"));
        assertTrue(aidl.contains("oneway void scheduleBindService"));
        assertTrue(aidl.contains("oneway void scheduleUnbindService"));
        assertTrue(aidl.contains("oneway void scheduleServiceArgs"));
        assertTrue(aidl.contains("oneway void scheduleStopService"));
        assertTrue(client.contains("isCurrentProcessGeneration(processGeneration)"));
        assertTrue(client.contains("sendMessage(FINISH_ACTIVITY, data)"));
        assertTrue(client.contains("VActivityManager.get().getActivityRecord(data.token) != null"));
    }

    @Test
    public void targetLivenessNeverSynchronouslyPingsGuestBinder() throws Exception {
        String activity = read("src/main/java/com/lody/virtual/server/am/ActivityStack.java");
        String service = read("src/main/java/com/lody/virtual/server/am/ServiceRecord.java");
        String vams = read("src/main/java/com/lody/virtual/server/am/VActivityManagerService.java");
        String isolated = read("src/main/java/com/lody/virtual/server/am/IsolatedGuestClient.java");

        assertFalse(activity.contains(".pingBinder()"));
        assertFalse(service.contains(".pingBinder()"));
        assertFalse(vams.contains(".pingBinder()"));
        assertFalse(isolated.contains(".pingBinder()"));
        assertTrue(activity.contains("mService.isCurrentActivityProcessOwner(processRecord)"));
        assertTrue(isolated.contains("processGeneration == generation"));
        assertTrue(isolated.contains("serviceToken == token"));
        assertTrue(vams.contains("process.lifecycle.state()"));
        assertTrue(vams.contains("isCurrentProcessOwner(process)"));
        assertTrue(vams.contains(".isBinderAlive()"));
    }

    @Test
    public void synchronousProviderUsesIndependentBoundedThawGate() throws Exception {
        String vams = read("src/main/java/com/lody/virtual/server/am/VActivityManagerService.java");
        String thaw = read(
                "src/main/java/com/lody/virtual/server/am/GuestProcessThawCoordinator.java");
        int providerStart = vams.indexOf("public IBinder acquireProviderClient");
        int providerEnd = vams.indexOf("public ComponentName getCallingActivity", providerStart);
        String provider = vams.substring(providerStart, providerEnd);

        assertTrue(provider.contains("mGuestProcessThawCoordinator.execute"));
        assertFalse(provider.contains("pingBinder"));
        assertTrue(thaw.contains("TIMEOUT_MILLIS = 3_000L"));
        assertTrue(thaw.contains("Looper.myLooper() == Looper.getMainLooper()"));
        assertTrue(thaw.contains("lease.references++"));
        assertTrue(thaw.contains("validator.isCurrentReadyOwner(process, lease.generation)"));
        assertTrue(thaw.contains("context.bindService"));
        assertTrue(thaw.contains("context.unbindService"));
        assertFalse(thaw.contains("StaticBroadcastDispatcher"));
        assertFalse(thaw.contains("LinePushProcessGuard"));
    }

    @Test
    public void deadAndStartupOrphansAreRemovedOutsideHistoryMutation() throws Exception {
        String stack = read("src/main/java/com/lody/virtual/server/am/ActivityStack.java");
        String vams = read("src/main/java/com/lody/virtual/server/am/VActivityManagerService.java");

        assertTrue(stack.contains("List<Integer> processDied(ProcessRecord record)"));
        assertTrue(stack.contains("if (r.process == record)"));
        assertTrue(vams.contains("scheduleOrphanedStubTaskReconciliation(null, \"engine-startup\")"));
        assertTrue(vams.contains("am.getAppTasks()"));
        assertTrue(vams.contains("mMainStack.hasLiveTaskOwnership(taskInfo.id)"));
        assertTrue(vams.contains("appTask.finishAndRemoveTask()"));
        assertTrue(vams.contains("state != ProcessLifecycle.State.STARTING"
                + " && state != ProcessLifecycle.State.READY"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
