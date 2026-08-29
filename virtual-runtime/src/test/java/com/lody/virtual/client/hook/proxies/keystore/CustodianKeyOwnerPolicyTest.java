package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class CustodianKeyOwnerPolicyTest {
    private static final String GROUP_ID = "34480577-4e1a-449f-bc5f-b5cbc02e8a7c";

    @Test
    public void lineUsesCanonicalGroupUuidInsteadOfNumericUserId() {
        assertTrue(CustodianKeyOwnerPolicy.requiresCustodian("jp.naver.line.android"));
        assertEquals(GROUP_ID, CustodianKeyOwnerPolicy.stableOwnerId(
                "jp.naver.line.android", "AppTwin:group:" + GROUP_ID + "|測試"));
    }

    @Test
    public void nonLineAndMalformedUsersFailClosed() {
        assertFalse(CustodianKeyOwnerPolicy.requiresCustodian("com.facebook.lite"));
        assertNull(CustodianKeyOwnerPolicy.stableOwnerId(
                "com.facebook.lite", "AppTwin:group:" + GROUP_ID));
        assertNull(CustodianKeyOwnerPolicy.stableOwnerId(
                "jp.naver.line.android", "AppTwin:group:not-a-uuid|測試"));
        assertNull(CustodianKeyOwnerPolicy.stableOwnerId(
                "jp.naver.line.android", "Guest"));
    }

    @Test
    public void aliasesRemainStableAcrossEnvironmentIdsAndIsolatedAcrossGroups() {
        String alias = CustodianAliasPolicy.toPhysicalAlias(
                GROUP_ID, "jp.naver.line.android", "line-session");
        assertEquals("line-session", CustodianAliasPolicy.toGuestAlias(
                GROUP_ID, "jp.naver.line.android", alias));
        assertTrue(CustodianAliasPolicy.isOwnedBy(
                GROUP_ID, "jp.naver.line.android", alias));
        assertFalse(CustodianAliasPolicy.isOwnedBy(
                "7fba3b64-10ac-4541-b7e4-14706040e272", "jp.naver.line.android", alias));
        assertEquals(Arrays.asList(alias), CustodianAliasPolicy.ownedAliasesForKeyspace(
                GROUP_ID,
                Arrays.asList("host-key", alias,
                        CustodianAliasPolicy.toPhysicalAlias(
                                "7fba3b64-10ac-4541-b7e4-14706040e272",
                                "jp.naver.line.android",
                                "line-session"))));
    }
}
