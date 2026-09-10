package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class LinePushClosedGateRecoveryIntegrationTest {
    @Test
    public void broadcastWrapperCarriesRegistrationTrustAndExactTargetIntoVams()
            throws Exception {
        String source = read("com/lody/virtual/server/am/BroadcastSystem.java");
        int receiver = source.indexOf("class StaticBroadcastReceiver");
        String body = source.substring(receiver);

        assertTrue(source.contains("componentFilter, true"));
        assertTrue(source.contains("internalFilter, true"));
        assertTrue(source.contains("systemFilter, false"));
        assertTrue(body.contains("final boolean signatureProtectedWrapper"));
        assertTrue(body.contains("virtualSenderPackage, virtualSenderVuid, virtualSenderUserId"));
        assertTrue(body.contains("BroadcastPackageScope.accepts(targetPackage, info.packageName)"));
    }

    @Test
    public void guestHookAttachesBoundSenderIdentityAndServerChecksExpectedGmsVuid()
            throws Exception {
        String hooks = read("com/lody/virtual/client/hook/proxies/am/MethodProxies.java");
        String components = read("com/lody/virtual/helper/utils/ComponentUtils.java");
        String broadcasts = read("com/lody/virtual/server/am/BroadcastSystem.java");
        String vams = read("com/lody/virtual/server/am/VActivityManagerService.java");

        assertTrue(hooks.contains("getAppPkg(), getVUid()"));
        assertTrue(components.contains("intent.cloneFilter()"));
        assertTrue(components.contains("EXTRA_VIRTUAL_SENDER_PACKAGE"));
        assertTrue(components.contains("EXTRA_VIRTUAL_SENDER_VUID"));
        assertTrue(broadcasts.contains("EXTRA_VIRTUAL_SENDER_USER_ID"));
        assertTrue(vams.contains("expectedSenderVuid"));
        assertTrue(vams.contains("gmsSetting.isInstalled(userId)"));
    }

    @Test
    public void attestationUsesRealBinderProcessAndIsConsumedOnlyByClosedGateRecovery()
            throws Exception {
        String aidl = new String(Files.readAllBytes(Paths.get(
                "src/main/aidl/com/lody/virtual/server/IActivityManager.aidl")),
                StandardCharsets.UTF_8);
        String hooks = read("com/lody/virtual/client/hook/proxies/am/MethodProxies.java");
        String vams = read("com/lody/virtual/server/am/VActivityManagerService.java");
        int issue = vams.indexOf("String issueLinePushBroadcastAttestation(");
        int nextOverride = vams.indexOf("@Override", issue + 10);
        String issueBody = vams.substring(issue, nextOverride);
        int broadcastEntry = vams.indexOf("boolean handleStaticBroadcastAsUser(");
        int closedGate = vams.indexOf("if (!beginDaemonWorkloadAcquisition())", broadcastEntry);
        int gateAllowed = vams.indexOf("gate-allow", closedGate);
        String rejectedPath = vams.substring(closedGate, gateAllowed);

        assertTrue(aidl.contains("String issueLinePushBroadcastAttestation("));
        assertTrue(issueBody.contains("Binder.getCallingPid()"));
        assertTrue(issueBody.contains("findProcessLocked(callingPid)"));
        assertTrue(issueBody.contains("caller.info.packageName"));
        assertTrue(issueBody.contains("caller.lifecycle.state() == ProcessLifecycle.State.READY"));
        assertTrue(issueBody.contains("mLinePushStopFence.acquire("));
        assertTrue(issueBody.contains("targetPackage, stopPermit"));
        assertTrue(hooks.contains("shouldRequestLinePushAttestation("));
        assertTrue(hooks.contains("issueLinePushBroadcastAttestation("));
        assertTrue(rejectedPath.contains("mLinePushBroadcastAttestations.consume("));
        assertTrue(rejectedPath.indexOf("mLinePushStopFence.acquire(")
                < rejectedPath.indexOf("mLinePushBroadcastAttestations.consume("));
        assertTrue(rejectedPath.contains("LinePushClosedGateRecoveryPolicy.isEligible("));
    }

    @Test
    public void recoveryPolicyReceivesOriginalPackageAndBothComponentScopes() throws Exception {
        String source = read("com/lody/virtual/server/am/VActivityManagerService.java");
        int entry = source.indexOf("boolean handleStaticBroadcast(int appId");
        int dispatch = source.indexOf("boolean dispatchStaticBroadcastWithAcquiredGate(", entry);
        String path = source.substring(entry, dispatch);

        assertTrue(path.contains("intent.getComponent() != null"));
        assertTrue(path.contains("component != null"));
        assertTrue(path.contains("intent == null ? null : intent.getPackage()"));
        assertTrue(path.contains("wrapperHasComponent"));
        assertTrue(path.contains("originalHasComponent"));
    }

    @Test
    public void closedGatePathRequestsRecoveryWithoutReopeningOrStartingGuestDirectly()
            throws Exception {
        String source = read("com/lody/virtual/server/am/VActivityManagerService.java");
        int entry = source.indexOf("boolean handleStaticBroadcastAsUser(");
        int retry = source.indexOf("boolean retryStaticBroadcastThroughNormalGate(", entry);
        String closedGatePath = source.substring(entry, retry);

        int normalGate = closedGatePath.indexOf("beginDaemonWorkloadAcquisition()");
        int eligibility = closedGatePath.indexOf(
                "LinePushClosedGateRecoveryPolicy.isEligible(");
        int modePolicy = closedGatePath.indexOf(
                "LinePushDeliveryPolicy.shouldRecoverClosedGate(");
        int recovery = closedGatePath.indexOf("daemonAuthorization.start(");
        assertTrue(normalGate >= 0);
        assertTrue(eligibility > normalGate);
        assertTrue(modePolicy > eligibility);
        assertTrue(recovery > modePolicy);
        assertFalse(closedGatePath.contains("reopenDaemonWorkloadGate("));
        assertFalse(closedGatePath.contains("startProcessIfNeedLocked("));
        assertFalse(closedGatePath.contains("durableDesiredUsers()"));
        assertTrue(closedGatePath.contains(
                "mTrustedGmsCloudMessagingSupervisor.isDesiredUser(userId)"));
    }

    @Test
    public void stageOneDefaultsToDirectBaselineAndClosedGateFailsBeforeRecovery()
            throws Exception {
        String source = read("com/lody/virtual/server/am/VActivityManagerService.java");
        int entry = source.indexOf("boolean handleStaticBroadcastAsUser(");
        int retry = source.indexOf("boolean retryStaticBroadcastThroughNormalGate(", entry);
        String closedGatePath = source.substring(entry, retry);

        assertTrue(source.contains("static final LinePushDeliveryMode LINE_PUSH_DELIVERY_MODE ="));
        assertTrue(source.contains("LinePushDeliveryMode.DIRECT_BASELINE;"));
        assertTrue(closedGatePath.contains("mLinePushBroadcastAttestations.consume("));
        assertTrue(closedGatePath.contains("LinePushDeliveryPolicy.shouldRecoverClosedGate("));
        assertTrue(closedGatePath.contains("direct-baseline-gate-closed"));
        assertFalse(closedGatePath.contains("reopenDaemonWorkloadGate("));
        assertFalse(closedGatePath.contains("startProcessIfNeedLocked("));
    }

    @Test
    public void retryReentersGateBeforeExistingStartGuardLeaseAndDispatchPipeline()
            throws Exception {
        String source = read("com/lody/virtual/server/am/VActivityManagerService.java");
        int retry = source.indexOf("boolean retryStaticBroadcastThroughNormalGate(");
        int finishCallback = source.indexOf("public void broadcastFinish(", retry);
        String retryAndDispatch = source.substring(retry, finishCallback);

        int executionPermit = retryAndDispatch.indexOf(
                "mLinePushClosedGateRecovery.isExecutionAllowed(recoveryToken)");
        int gate = retryAndDispatch.indexOf("beginDaemonWorkloadAcquisition()");
        int startPolicy = retryAndDispatch.indexOf("LinePushBroadcastPolicy.shouldStart(");
        int dispatch = retryAndDispatch.indexOf("mStaticBroadcastDispatcher.enqueue(");
        assertTrue(executionPermit >= 0);
        assertTrue(gate > executionPermit);
        assertTrue(startPolicy > gate);
        assertTrue(dispatch > startPolicy);
        assertTrue(retryAndDispatch.contains("endDaemonWorkloadMutation();"));
    }

    @Test
    public void daemonRequestAndRetryShareTheSameVamsStopFence() throws Exception {
        String source = read("com/lody/virtual/server/am/VActivityManagerService.java");
        int request = source.indexOf("boolean requestLinePushRecoveryThroughFence(");
        int retry = source.indexOf("boolean retryStaticBroadcastThroughNormalGate(", request);
        int dispatch = source.indexOf("boolean dispatchStaticBroadcastWithAcquiredGate(", retry);
        String requestBody = source.substring(request, retry);
        String retryBody = source.substring(retry, dispatch);

        assertTrue(source.substring(Math.max(0, request - 30), request)
                .contains("private synchronized"));
        assertTrue(source.substring(Math.max(0, retry - 30), retry)
                .contains("private synchronized"));
        assertTrue(requestBody.indexOf("isExecutionAllowed(recoveryToken)")
                < requestBody.indexOf("daemonAuthorization.start("));
        assertTrue(requestBody.indexOf("mLinePushStopFence.isCurrent(stopPermit)")
                < requestBody.indexOf("daemonAuthorization.start("));
        assertTrue(retryBody.indexOf("isExecutionAllowed(recoveryToken)")
                < retryBody.indexOf("beginDaemonWorkloadAcquisition()"));
        assertTrue(retryBody.indexOf("mLinePushStopFence.isCurrent(stopPermit)")
                < retryBody.indexOf("beginDaemonWorkloadAcquisition()"));
        assertTrue(source.contains("private synchronized LinePushStopFence.StopScope beginLinePushStop"));
    }

    @Test
    public void explicitPackageAndUserStopsCancelPendingRecovery() throws Exception {
        String broadcasts = read("com/lody/virtual/server/am/BroadcastSystem.java");
        String vams = read("com/lody/virtual/server/am/VActivityManagerService.java");

        assertTrue(broadcasts.contains("mAMS.beginStaticBroadcastAppStop(packageName)"));
        assertTrue(broadcasts.contains("mAMS.endStaticBroadcastAppStop(stopScope)"));
        assertTrue(vams.contains("mLinePushClosedGateRecovery.cancelPackageUser(packageName, userId)"));
        assertTrue(vams.contains("mLinePushProcessGuard.cancelPackageUser(packageName, userId)"));
        assertTrue(vams.contains("LinePushStopFence.StopScope lineStop = beginLinePushStop(pkg, userId)"));
        assertTrue(vams.contains("endLinePushStop(lineStop)"));
        assertTrue(vams.contains("recovery-user-no-longer-desired"));
    }

    @Test
    public void packageClearAndUninstallKeepFenceAcrossTheWholeMutation() throws Exception {
        String apps = read("com/lody/virtual/server/pm/VAppManagerService.java");

        assertMutationFenced(apps, "boolean clearPackageAsUser(",
                "boolean clearPackageRuntimeStateAsUser(");
        assertMutationFenced(apps, "boolean clearPackageInternal(",
                "boolean uninstallPackageAsUser(");
        assertMutationFenced(apps, "boolean uninstallPackageAsUser(",
                "boolean clearTrustedPackageStateForUser(");
        assertMutationFenced(apps, "boolean clearPackageRuntimeState(",
                "boolean hasPackageDataState(");
        assertMutationFenced(apps, "boolean uninstallPackageFully(",
                "int[] getPackageInstalledUsers(");
    }

    @Test
    public void queuedDispatcherRechecksStopEpochBeforeOnewayDispatch()
            throws Exception {
        String source = read("com/lody/virtual/server/am/VActivityManagerService.java");
        int dispatch = source.indexOf("boolean dispatchStaticBroadcastWithAcquiredGate(");
        int stop = source.indexOf(
                "synchronized StaticBroadcastStopScope beginStaticBroadcastAppStop", dispatch);
        String path = source.substring(dispatch, stop);

        assertTrue(path.contains("mLinePushStopFence.acquire(info.packageName, userId)"));
        assertTrue(path.contains("mBroadcastDispatchStopFence.acquire(info.packageName, userId)"));
        assertTrue(path.contains("mStaticBroadcastDispatcher.enqueue"));
        assertTrue(path.indexOf("mBroadcastDispatchStopFence.acquire(info.packageName, userId)")
                < path.indexOf("mStaticBroadcastDispatcher.enqueue"));
        assertTrue(path.indexOf("mLinePushStopFence.isCurrent(dispatchStopPermit)")
                < path.lastIndexOf("mStaticBroadcastDispatcher.enqueue"));
    }

    @Test
    public void consumedNonceAndStopEpochAreValidatedWithTheAtomicVamsReopenFence()
            throws Exception {
        String daemon = readClient("com/lody/virtual/client/stub/DaemonService.java");
        String vams = read("com/lody/virtual/server/am/VActivityManagerService.java");
        int authorize = vams.indexOf("boolean authorizeLinePushDaemonReopen(long nonce)");
        int discard = vams.indexOf("void discardLinePushDaemonAuthorization", authorize);
        String authorizeBody = vams.substring(authorize, discard);

        assertTrue(vams.substring(Math.max(0, authorize - 30), authorize)
                .contains("public synchronized"));
        assertTrue(authorizeBody.indexOf("mLinePushDaemonAuthorizations.remove(nonce)")
                < authorizeBody.indexOf("mLinePushStopFence.isCurrent(scope.stopPermit)"));
        assertTrue(authorizeBody.indexOf("mLinePushStopFence.isCurrent(scope.stopPermit)")
                < authorizeBody.indexOf("mDaemonWorkloadGate.reopen()"));
        assertTrue(daemon.contains("activityManager.authorizeLinePushDaemonReopen("));
        assertTrue(daemon.contains(
                "linePushRecovery != LinePushRecoveryAuthorization.AUTHORIZED"));
        int register = vams.indexOf("mLinePushDaemonAuthorizations.put(authorizedNonce");
        int enqueue = vams.indexOf(
                "DaemonService.startPreparedLinePushRecovery(context, authorizedNonce)");
        assertTrue(register >= 0);
        assertTrue(enqueue > register);
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java", relativePath)),
                StandardCharsets.UTF_8);
    }

    private static String readClient(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java", relativePath)),
                StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void assertMutationFenced(
            String source, String method, String nextMethod) {
        int start = source.indexOf(method);
        int end = source.indexOf(nextMethod, start + method.length());
        String body = source.substring(start, end);
        int beginFence = body.indexOf("beginLinePushPackageStateMutation(");
        int kill = body.indexOf("killAppByPkg(");
        int endFence = body.lastIndexOf("endLinePushPackageStateMutation(");
        assertTrue(beginFence >= 0);
        assertTrue(kill > beginFence);
        assertTrue(endFence > kill);
        assertTrue(body.substring(Math.max(0, endFence - 80), endFence).contains("finally"));
    }
}
