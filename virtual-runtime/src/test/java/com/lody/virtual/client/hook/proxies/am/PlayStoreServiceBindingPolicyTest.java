package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayStoreServiceBindingPolicyTest {
    @Test
    public void rejectsOnlyPlayStoresLocalFirebaseMessagingBinder() {
        assertTrue(PlayStoreServiceBindingPolicy.shouldRejectLocalOnlyBinding(
                PlayStoreServiceBindingPolicy.PLAY_STORE_PACKAGE,
                PlayStoreServiceBindingPolicy.PLAY_STORE_PACKAGE,
                PlayStoreServiceBindingPolicy.FIREBASE_MESSAGING_SERVICE));
    }

    @Test
    public void preservesCrossPackageAndOtherPlayServiceBindings() {
        assertFalse(PlayStoreServiceBindingPolicy.shouldRejectLocalOnlyBinding(
                PlayStoreServiceBindingPolicy.PLAY_STORE_PACKAGE,
                "com.google.android.gms",
                "com.google.android.gms.chimera.PersistentApiService"));
        assertFalse(PlayStoreServiceBindingPolicy.shouldRejectLocalOnlyBinding(
                PlayStoreServiceBindingPolicy.PLAY_STORE_PACKAGE,
                PlayStoreServiceBindingPolicy.PLAY_STORE_PACKAGE,
                "com.google.frameworks.client.data.android.server.play.BackgroundProcessEndpointService"));
        assertFalse(PlayStoreServiceBindingPolicy.shouldRejectLocalOnlyBinding(
                "example.client",
                PlayStoreServiceBindingPolicy.PLAY_STORE_PACKAGE,
                PlayStoreServiceBindingPolicy.FIREBASE_MESSAGING_SERVICE));
    }
}
