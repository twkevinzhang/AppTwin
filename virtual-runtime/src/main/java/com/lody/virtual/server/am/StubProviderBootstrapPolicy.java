package com.lody.virtual.server.am;

/** Defines the provider ownership used only during a Stub process bootstrap handshake. */
final class StubProviderBootstrapPolicy {

    private StubProviderBootstrapPolicy() {
    }

    static boolean useStableProviderDependency() {
        return false;
    }

    static boolean retainProviderAfterHandshake() {
        return false;
    }
}
