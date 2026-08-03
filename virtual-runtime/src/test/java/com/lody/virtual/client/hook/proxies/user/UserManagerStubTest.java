package com.lody.virtual.client.hook.proxies.user;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UserManagerStubTest {

    @Test
    public void virtualUserIsAFullSecondaryUser() {
        assertTrue(UserManagerStub.isVirtualUserOfType("android.os.usertype.full.SECONDARY"));
    }

    @Test
    public void virtualUserDoesNotInheritOsProfileTypes() {
        assertFalse(UserManagerStub.isVirtualUserOfType("android.os.usertype.full.GUEST"));
        assertFalse(UserManagerStub.isVirtualUserOfType("android.os.usertype.profile.MANAGED"));
        assertFalse(UserManagerStub.isVirtualUserOfType("android.os.usertype.profile.CLONE"));
        assertFalse(UserManagerStub.isVirtualUserOfType("android.os.usertype.full.SYSTEM"));
        assertFalse(UserManagerStub.isVirtualUserOfType(null));
    }
}
