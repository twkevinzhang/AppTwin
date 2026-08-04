package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PackageSignaturePolicyTest {
    @Test
    public void acceptsTheSameSignatureSetRegardlessOfOrder() {
        byte[] first = signature(1);
        byte[] second = signature(2);

        assertTrue(PackageSignaturePolicy.isCompatible(
                new byte[][]{first, second},
                new byte[][]{second, first}));
    }

    @Test
    public void rejectsMissingChangedAndUnknownSignatures() {
        byte[] trusted = signature(1);

        assertFalse(PackageSignaturePolicy.isCompatible(
                new byte[][]{trusted}, new byte[][]{signature(2)}));
        assertFalse(PackageSignaturePolicy.isCompatible(new byte[][]{trusted}, null));
        assertFalse(PackageSignaturePolicy.isCompatible(
                new byte[][]{trusted}, new byte[][]{trusted, signature(2)}));
    }

    private static byte[] signature(int value) {
        return new byte[]{(byte) value};
    }
}
