package com.lody.virtual.client.hook.proxies.locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocaleManagerStubTest {
    @Test
    public void rewritesGuestPackageAndVirtualUserToHostOperatingSystemIdentity() {
        Object[] ownerArgs = {"jp.naver.line.android", 7};
        assertTrue(LocaleManagerStub.rewriteApplicationLocalesArgs(
                ownerArgs, "org.apptwin", 11060));
        assertArrayEquals(new Object[]{"org.apptwin", 0}, ownerArgs);

        Object[] secondaryUserArgs = {"jp.naver.line.android", 7, "unchanged"};
        assertTrue(LocaleManagerStub.rewriteApplicationLocalesArgs(
                secondaryUserArgs, "org.apptwin", 111060));
        assertArrayEquals(new Object[]{"org.apptwin", 1, "unchanged"}, secondaryUserArgs);
    }

    @Test
    public void leavesUnknownDescriptorsUntouched() {
        Object[][] malformedArgs = {
                {},
                {"jp.naver.line.android"},
                {42, 0},
                {"jp.naver.line.android", "0"}
        };

        for (Object[] args : malformedArgs) {
            Object[] original = args.clone();
            assertFalse(LocaleManagerStub.rewriteApplicationLocalesArgs(
                    args, "org.apptwin", 11060));
            assertArrayEquals(original, args);
        }

        Object[] validShape = {"jp.naver.line.android", 0};
        assertFalse(LocaleManagerStub.rewriteApplicationLocalesArgs(validShape, null, 11060));
        assertArrayEquals(new Object[]{"jp.naver.line.android", 0}, validShape);
        assertFalse(LocaleManagerStub.rewriteApplicationLocalesArgs(null,
                "org.apptwin", 11060));
    }
}
