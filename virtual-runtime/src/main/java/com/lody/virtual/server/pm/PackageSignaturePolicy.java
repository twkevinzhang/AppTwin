package com.lody.virtual.server.pm;

import android.content.pm.Signature;

import java.util.Arrays;

/** Pure signature-set comparison used before shared package code is rebound or replaced. */
final class PackageSignaturePolicy {
    private PackageSignaturePolicy() {
    }

    static boolean isCompatible(Signature[] existing, Signature[] staged) {
        return isCompatible(toByteArrays(existing), toByteArrays(staged));
    }

    static boolean isCompatible(byte[][] existing, byte[][] staged) {
        if (existing == null || staged == null || existing.length == 0
                || existing.length != staged.length) {
            return false;
        }
        boolean[] matched = new boolean[staged.length];
        for (byte[] current : existing) {
            boolean found = false;
            for (int i = 0; i < staged.length; i++) {
                if (!matched[i] && Arrays.equals(current, staged[i])) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static byte[][] toByteArrays(Signature[] signatures) {
        if (signatures == null) {
            return null;
        }
        byte[][] result = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) {
            result[i] = signatures[i].toByteArray();
        }
        return result;
    }
}
