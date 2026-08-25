package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;

import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;

import org.junit.Test;

/** Structural integration checks for the Binder-authenticated MCS reconnect bridge. */
public class VActivityManagerServiceTrustedMcsBridgeTest {
    @Test
    public void reconnectObservationRunsBeforeAnyTargetProcessOrServiceDispatch() throws Exception {
        String source = readServiceSource();
        int common = source.indexOf(
                "private ComponentName startServiceCommon(IBinder caller, Intent service,");
        int observe = source.indexOf(
                "notifyTrustedGmsMcsReconnectIfNeeded(caller, service, serviceInfo, userId);",
                common);
        int startTarget = source.indexOf("final ProcessRecord targetApp", common);
        int dispatchArgs = source.indexOf("scheduleServiceArgs(", common);

        assertTrue(common >= 0);
        assertTrue(observe > common);
        assertTrue(startTarget > observe);
        assertTrue(dispatchArgs > observe);
    }

    @Test
    public void bridgeAuthenticatesRuntimeIdentityBeforeClassifyingIntentMetadata()
            throws Exception {
        String source = readServiceSource();
        int bridge = source.indexOf("void notifyTrustedGmsMcsReconnectIfNeeded(");
        int stopService = source.indexOf("public int stopService(", bridge);
        String body = source.substring(bridge, stopService);

        assertTrue(body.contains("Binder.getCallingPid()"));
        assertTrue(body.contains("findProcessLocked(callingPid)"));
        assertTrue(body.contains("callerRecord.userId != userId"));
        assertTrue(body.contains("sameBinderHandle(expectedCaller, caller)"));
        assertTrue(body.contains("callerRecord.lifecycle.state() != ProcessLifecycle.State.READY"));
        assertTrue(body.contains("!isCurrentProcessOwner(callerRecord)"));
        assertTrue(body.contains("!isProcessEndpointActive(callerRecord)"));
        assertTrue(body.contains("requestedTarget == null || !requestedTarget.equals(resolvedTarget)"));

        int identity = body.indexOf("Binder.getCallingPid()");
        int metadata = body.indexOf(
                "TrustedGmsMcsReconnectSignalPolicy.isTrustedReconnectSignal(");
        int mutation = body.indexOf("onMcsReconnectRequired(");
        assertTrue(metadata > identity);
        assertTrue(mutation > metadata);
    }

    @Test
    public void onlyNestedMicrogTriggerIntentCanSupplyReconnectReason() throws Exception {
        String source = readServiceSource();
        int bridge = source.indexOf("void notifyTrustedGmsMcsReconnectIfNeeded(");
        int stopService = source.indexOf("public int stopService(", bridge);
        String body = source.substring(bridge, stopService);

        assertTrue(body.contains("rawReason instanceof Intent"));
        assertTrue(body.contains("((Intent) rawReason).getAction()"));
        assertFalse(body.contains("getStringExtra("));
    }

    @Test
    public void binderAuthenticationAcceptsEquivalentHandleButRejectsWrongOrMissingHandle() {
        IBinder recordedProxy = new FakeBinderHandle("persistent-app-thread");
        IBinder equivalentProxy = new FakeBinderHandle("persistent-app-thread");
        IBinder wrongProxy = new FakeBinderHandle("other-app-thread");

        assertTrue(VActivityManagerService.sameBinderHandle(recordedProxy, equivalentProxy));
        assertFalse(VActivityManagerService.sameBinderHandle(recordedProxy, wrongProxy));
        assertFalse(VActivityManagerService.sameBinderHandle(recordedProxy, null));
        assertFalse(VActivityManagerService.sameBinderHandle(null, equivalentProxy));
    }

    @Test
    public void supervisorObservationFailureCannotBlockMicrogServiceStart() throws Exception {
        String source = readServiceSource();
        int bridge = source.indexOf("void notifyTrustedGmsMcsReconnectIfNeeded(");
        int stopService = source.indexOf("public int stopService(", bridge);
        String body = source.substring(bridge, stopService);

        assertTrue(body.contains("catch (RuntimeException supervisorFailure)"));
        assertFalse(body.contains("throw supervisorFailure"));
    }

    private static String readServiceSource() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(
                "src/main/java/com/lody/virtual/server/am/VActivityManagerService.java"));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class FakeBinderHandle implements IBinder {
        private final String handle;

        FakeBinderHandle(String handle) {
            this.handle = handle;
        }

        @Override public boolean equals(Object other) {
            return other instanceof FakeBinderHandle
                    && Objects.equals(handle, ((FakeBinderHandle) other).handle);
        }

        @Override public int hashCode() {
            return Objects.hash(handle);
        }

        @Override public String getInterfaceDescriptor() {
            return "test." + handle;
        }

        @Override public boolean pingBinder() {
            return true;
        }

        @Override public boolean isBinderAlive() {
            return true;
        }

        @Override public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override public void dump(FileDescriptor fd, String[] args) {
        }

        @Override public void dumpAsync(FileDescriptor fd, String[] args) {
        }

        @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) {
            return false;
        }

        @Override public void linkToDeath(DeathRecipient recipient, int flags) {
        }

        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return false;
        }
    }
}
