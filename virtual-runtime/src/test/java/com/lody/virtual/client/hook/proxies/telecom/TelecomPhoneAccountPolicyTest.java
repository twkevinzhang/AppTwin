package com.lody.virtual.client.hook.proxies.telecom;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TelecomPhoneAccountPolicyTest {
    @Test
    public void lineIdentityRoundTripsWithoutLosingOriginalHandle() {
        String encoded = TelecomPhoneAccountPolicy.encode(
                7,
                "jp.naver.line.android",
                "com.linecorp.andromeda.core.AndromedaConnectionService",
                "andromeda:phone/account");

        TelecomPhoneAccountPolicy.Identity decoded =
                TelecomPhoneAccountPolicy.decode(encoded);

        assertEquals(7, decoded.virtualUserId);
        assertEquals("jp.naver.line.android", decoded.packageName);
        assertEquals(
                "com.linecorp.andromeda.core.AndromedaConnectionService",
                decoded.className);
        assertEquals("andromeda:phone/account", decoded.originalId);
    }

    @Test
    public void malformedOrTrailingIdentityIsRejected() {
        assertNull(TelecomPhoneAccountPolicy.decode(null));
        assertNull(TelecomPhoneAccountPolicy.decode("native-account"));
        assertNull(TelecomPhoneAccountPolicy.decode("apptwin:telecom:1:0:99:short"));

        String valid = TelecomPhoneAccountPolicy.encode(
                0,
                "jp.naver.line.android",
                "com.linecorp.andromeda.core.AndromedaConnectionService",
                "id");
        assertNull(TelecomPhoneAccountPolicy.decode(valid + "trailing"));
    }

    @Test
    public void routeRequiresExactLineComponentPackageAndVirtualUser() {
        TelecomPhoneAccountPolicy.Identity identity = TelecomPhoneAccountPolicy.decode(
                TelecomPhoneAccountPolicy.encode(
                        3,
                        "jp.naver.line.android",
                        "com.linecorp.andromeda.core.AndromedaConnectionService",
                        "id"));

        assertTrue(TelecomPhoneAccountPolicy.canRoute(
                identity, "jp.naver.line.android", 3));
        assertFalse(TelecomPhoneAccountPolicy.canRoute(
                identity, "jp.naver.line.android", 4));
        assertFalse(TelecomPhoneAccountPolicy.canRoute(identity, "other.package", 3));
        assertFalse(TelecomPhoneAccountPolicy.supports(
                "jp.naver.line.android", "other.ConnectionService"));
    }

    @Test
    public void currentHostProcessMapsToMatchingDeclaredSlot() {
        assertEquals(0, TelecomPhoneAccountPolicy.parseStubSlot("org.apptwin", "org.apptwin:p0"));
        assertEquals(49, TelecomPhoneAccountPolicy.parseStubSlot("org.apptwin", "org.apptwin:p49"));
        assertEquals(-1, TelecomPhoneAccountPolicy.parseStubSlot("org.apptwin", "org.apptwin"));
        assertEquals(-1, TelecomPhoneAccountPolicy.parseStubSlot("org.apptwin", "org.apptwin:p50"));
        assertEquals(
                "com.lody.virtual.client.stub.StubConnectionService$C12",
                TelecomPhoneAccountPolicy.stubClassName(12));
        assertNull(TelecomPhoneAccountPolicy.stubClassName(-1));
    }
}
