package com.lody.virtual.client.fixer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ContextFixerTest {

    @Test
    public void fixAttributionSource_rewritesEntireChainToBinderIdentity() {
        FakeAttributionSource next = new FakeAttributionSource(10004, "guest.next", null);
        FakeAttributionSource source = new FakeAttributionSource(10004, "guest.root", next);

        ContextFixer.fixAttributionSource(source, "org.apptwin", 10959);

        assertEquals(10959, source.mAttributionSourceState.uid);
        assertEquals("org.apptwin", source.mAttributionSourceState.packageName);
        assertEquals(10959, next.mAttributionSourceState.uid);
        assertEquals("org.apptwin", next.mAttributionSourceState.packageName);
    }

    private static final class FakeAttributionSource {
        private final FakeAttributionSourceState mAttributionSourceState;
        private final FakeAttributionSource next;

        private FakeAttributionSource(int uid, String packageName, FakeAttributionSource next) {
            this.mAttributionSourceState = new FakeAttributionSourceState(uid, packageName);
            this.next = next;
        }

        @SuppressWarnings("unused")
        public FakeAttributionSource getNext() {
            return next;
        }
    }

    private static final class FakeAttributionSourceState {
        private int uid;
        private String packageName;

        private FakeAttributionSourceState(int uid, String packageName) {
            this.uid = uid;
            this.packageName = packageName;
        }
    }
}
