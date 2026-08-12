package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class StubProviderBootstrapPolicyTest {

    @Test
    public void subprocessExitCannotTakeDownEngineThroughStableProviderDependency() {
        assertFalse(StubProviderBootstrapPolicy.useStableProviderDependency());
        assertFalse(StubProviderBootstrapPolicy.retainProviderAfterHandshake());
    }
}
