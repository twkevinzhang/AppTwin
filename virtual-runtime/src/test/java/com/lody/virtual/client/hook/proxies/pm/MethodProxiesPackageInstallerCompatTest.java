package com.lody.virtual.client.hook.proxies.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MethodProxiesPackageInstallerCompatTest {
    @Test
    public void recognizesModernStagedSessionQueries() {
        assertTrue(MethodProxies.isStagedSessionQuery("getStagedSessions"));
        assertTrue(MethodProxies.isStagedSessionQuery("getActiveStagedSession"));
    }

    @Test
    public void doesNotClassifyMutatingInstallerMethodsAsQueries() {
        assertFalse(MethodProxies.isStagedSessionQuery("createSession"));
        assertFalse(MethodProxies.isStagedSessionQuery("commit"));
        assertFalse(MethodProxies.isStagedSessionQuery(null));
    }
}
