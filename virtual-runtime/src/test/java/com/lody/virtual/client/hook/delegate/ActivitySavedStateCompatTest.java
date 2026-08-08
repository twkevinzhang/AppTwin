package com.lody.virtual.client.hook.delegate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class ActivitySavedStateCompatTest {

    @Test
    public void recreationPreservesPayloadAndUsesGuestClassLoader() {
        FakeSavedState state = new FakeSavedState("secondary-login-navigation-state");
        ClassLoader guestClassLoader = new ClassLoader() {
        };

        ActivitySavedStateCompat.prepare(state, guestClassLoader);

        assertEquals("secondary-login-navigation-state", state.payload);
        assertSame(guestClassLoader, state.classLoader);
    }

    @Test
    public void missingClassLoaderLeavesStateUntouched() {
        FakeSavedState state = new FakeSavedState("sync-in-progress");

        ActivitySavedStateCompat.prepare(state, null);

        assertEquals("sync-in-progress", state.payload);
        assertNull(state.classLoader);
    }

    @Test
    public void missingStateIsSafe() {
        ActivitySavedStateCompat.prepare((ActivitySavedStateCompat.ClassLoaderTarget) null,
                getClass().getClassLoader());
    }

    private static final class FakeSavedState
            implements ActivitySavedStateCompat.ClassLoaderTarget {
        private final String payload;
        private ClassLoader classLoader;

        private FakeSavedState(String payload) {
            this.payload = payload;
        }

        @Override
        public void setClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }
    }
}
