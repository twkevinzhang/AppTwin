package com.lody.virtual.client.hook.delegate;

import android.os.Bundle;

/**
 * Prepares guest activity state for unparcelling without discarding it.
 *
 * <p>The platform can recreate an activity after an authentication or configuration transition.
 * Clearing the saved-state parcel here removes AndroidX SavedStateRegistry and Compose navigation
 * state, leaving the recreated activity alive but without any visible destination.</p>
 */
final class ActivitySavedStateCompat {

    interface ClassLoaderTarget {
        void setClassLoader(ClassLoader classLoader);
    }

    private ActivitySavedStateCompat() {
    }

    static void prepare(Bundle savedState, ClassLoader guestClassLoader) {
        if (savedState == null) {
            return;
        }
        prepare(savedState::setClassLoader, guestClassLoader);
    }

    static void prepare(ClassLoaderTarget target, ClassLoader guestClassLoader) {
        if (target != null && guestClassLoader != null) {
            target.setClassLoader(guestClassLoader);
        }
    }
}
