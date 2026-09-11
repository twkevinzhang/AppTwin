package com.lody.virtual.client.hook.providers;

import static org.junit.Assert.*;

import org.junit.Test;

public class ProviderAttributionCallCopyTest {
    private static final String HOST = "org.apptwin";
    private static final String GUEST = "app.revanced.android.youtube";

    @Test
    public void internalCallCannotPolluteGlobalContextOrDelegatedChain() {
        Source next = new Source(9988, "other.principal", null);
        Source global = new Source(11291, HOST, next);
        Source call = copy(global, GUEST);

        assertNotSame(global, call);
        assertNotSame(global.mAttributionSourceState, call.mAttributionSourceState);
        assertEquals(HOST, global.mAttributionSourceState.packageName);
        assertEquals(GUEST, call.mAttributionSourceState.packageName);
        assertEquals(11291, call.mAttributionSourceState.uid);
        assertSame(next, call.mAttributionSourceState.next);
        assertEquals("other.principal", next.mAttributionSourceState.packageName);
        assertEquals(9988, next.mAttributionSourceState.uid);
        assertSame(global.mAttributionSourceState.token, call.mAttributionSourceState.token);
        assertSame(global.mAttributionSourceState.permissions,
                call.mAttributionSourceState.permissions);
        assertEquals(global.mAttributionSourceState.tag, call.mAttributionSourceState.tag);
        assertEquals(1234, call.mAttributionSourceState.pid);
        assertEquals(7, call.mAttributionSourceState.deviceId);
    }

    @Test
    public void externalCallCopiesVirtualIdentityWithoutTouchingSource() {
        Source guest = new Source(10004, GUEST, null);
        Source call = copy(guest, HOST);
        assertEquals(HOST, call.mAttributionSourceState.packageName);
        assertEquals(11291, call.mAttributionSourceState.uid);
        assertEquals(GUEST, guest.mAttributionSourceState.packageName);
        assertEquals(10004, guest.mAttributionSourceState.uid);
    }

    @Test
    public void unrelatedPackageOrUidNeverGetsReattributed() {
        Source otherPackage = new Source(11291, "other.principal", null);
        Source otherUid = new Source(9988, GUEST, null);
        assertSame(otherPackage, copy(otherPackage, HOST));
        assertSame(otherUid, copy(otherUid, HOST));
    }

    @Test
    public void independentCallsCannotObserveEachOthersTranslation() {
        Source global = new Source(11291, HOST, null);
        Source internal = copy(global, GUEST);
        Source external = copy(global, HOST);
        assertSame(global, external);
        assertEquals(GUEST, internal.mAttributionSourceState.packageName);
        assertEquals(HOST, external.mAttributionSourceState.packageName);
        assertNotSame(internal, copy(global, GUEST));
    }

    @Test(expected = IllegalStateException.class)
    public void brokenCopyCannotMutateSharedState() {
        Source global = new Source(11291, HOST, null);
        try {
            ProviderAttributionCallCopy.copyForCall(global, GUEST, HOST, 10004, 11291,
                    GUEST, original -> original);
        } finally {
            assertEquals(HOST, global.mAttributionSourceState.packageName);
        }
    }

    @Test
    public void runtimeStateCopyPreservesFutureAndInheritedFields() {
        Source original = new Source(11291, HOST, new Source(9988, "other", null));
        Object marker = new Object();
        original.mAttributionSourceState.futureField = marker;
        original.mAttributionSourceState.inheritedField = 9876;
        Source result = (Source) ProviderAttributionCallCopy.copyForCall(original,
                GUEST, HOST, 10004, 11291, GUEST);
        assertNotSame(original.mAttributionSourceState, result.mAttributionSourceState);
        assertSame(marker, result.mAttributionSourceState.futureField);
        assertEquals(9876, result.mAttributionSourceState.inheritedField);
        assertSame(original.mAttributionSourceState.next, result.mAttributionSourceState.next);
        assertSame(original.mAttributionSourceState.token, result.mAttributionSourceState.token);
        assertEquals(HOST, original.mAttributionSourceState.packageName);
        assertEquals(GUEST, result.mAttributionSourceState.packageName);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unrelatedTargetIsRejectedBeforeCopying() {
        copy(new Source(11291, HOST, null), "unrelated.target");
    }

    private static Source copy(Source source, String target) {
        return (Source) ProviderAttributionCallCopy.copyForCall(source, GUEST, HOST,
                10004, 11291, target, original -> new Source((Source) original));
    }

    private static final class Source {
        private final State mAttributionSourceState;
        Source(int uid, String pkg, Source next) {
            mAttributionSourceState = new State(uid, pkg, next);
        }
        Source(State state) {
            mAttributionSourceState = state;
        }
        Source(Source original) {
            State old = original.mAttributionSourceState;
            mAttributionSourceState = new State(old.uid, old.packageName, old.next);
            mAttributionSourceState.token = old.token;
            mAttributionSourceState.permissions = old.permissions;
            mAttributionSourceState.tag = old.tag;
            mAttributionSourceState.pid = old.pid;
            mAttributionSourceState.deviceId = old.deviceId;
        }
    }

    private static class BaseState {
        long inheritedField;
    }

    private static final class State extends BaseState {
        int uid;
        String packageName;
        Source next;
        Object futureField;
        Object token = new Object();
        String[] permissions = {"test.permission"};
        String tag = "fixture";
        int pid = 1234;
        int deviceId = 7;
        State() { }
        State(int uid, String pkg, Source next) {
            this.uid = uid;
            this.packageName = pkg;
            this.next = next;
        }
    }
}
