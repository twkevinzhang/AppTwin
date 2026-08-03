package com.lody.virtual.server.pm.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PackageParserExSigningInfoCompatTest {
    @Test
    public void keepsLegacySigningInfoConstructorThroughAndroid12L() {
        assertFalse(PackageParserEx.requiresTopLevelSigningDetails(28));
        assertFalse(PackageParserEx.requiresTopLevelSigningDetails(31));
        assertFalse(PackageParserEx.requiresTopLevelSigningDetails(32));
    }

    @Test
    public void rebuildsTopLevelSigningDetailsFromAndroid13() {
        assertTrue(PackageParserEx.requiresTopLevelSigningDetails(33));
        assertTrue(PackageParserEx.requiresTopLevelSigningDetails(37));
    }
}
