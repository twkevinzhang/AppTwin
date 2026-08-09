package com.lody.virtual.client.stub;

import android.content.Intent;

import com.lody.virtual.os.VUserManager;
import com.lody.virtual.os.VUserInfo;

/** Rejects host PendingIntent tokens created for an earlier owner of a reused numeric user id. */
public final class PendingIntentUserGeneration {
    public static final String EXTRA_SERIAL = "_VA_|_user_serial_";
    public static final String EXTRA_EPOCH = "_VA_|_pending_intent_generation_";
    public static final String EXTRA_PACKAGE_EPOCH = "_VA_|_package_pending_intent_generation_";
    private static final String IDENTITY_CATEGORY_PREFIX = "_VA_|_pi_generation_|";

    private PendingIntentUserGeneration() {
    }

    public static boolean isCurrent(Intent redirect, int userId) {
        int embedded = redirect.getIntExtra(EXTRA_SERIAL, -1);
        long embeddedEpoch = redirect.getLongExtra(EXTRA_EPOCH, 0L);
        long embeddedPackageEpoch = redirect.getLongExtra(EXTRA_PACKAGE_EPOCH, 0L);
        String creator = redirect.getStringExtra("_VA_|_creator_");
        VUserInfo current = VUserManager.get().getUserInfo(userId);
        long currentPackageEpoch = creator == null ? 0L
                : VUserManager.get().getPackagePendingIntentGeneration(creator, userId);
        return current != null && hasIdentityCategory(
                redirect, creator, userId, embedded, embeddedEpoch, embeddedPackageEpoch)
                && matches(
                embedded, embeddedEpoch, embeddedPackageEpoch,
                current.serialNumber, current.pendingIntentGeneration, currentPackageEpoch);
    }

    /**
     * PendingIntent equality ignores extras.  Put the ownership generation in the outer wrapper's
     * filter identity so FLAG_UPDATE_CURRENT after reset cannot reactivate an old host token.
     */
    public static String identityCategory(
            String creator, int userId, int serial, long userEpoch, long packageEpoch) {
        if (creator == null || creator.isEmpty() || serial < 0
                || userEpoch == 0L || packageEpoch == 0L) return null;
        return IDENTITY_CATEGORY_PREFIX + creator + "|" + userId + "|" + serial + "|"
                + Long.toUnsignedString(userEpoch) + "|" + Long.toUnsignedString(packageEpoch);
    }

    static boolean hasIdentityCategory(
            Intent redirect, String creator, int userId, int serial,
            long userEpoch, long packageEpoch) {
        String category = identityCategory(creator, userId, serial, userEpoch, packageEpoch);
        return category != null && redirect.getCategories() != null
                && redirect.getCategories().contains(category);
    }

    static boolean matches(
            int embeddedSerial,
            long embeddedEpoch,
            long embeddedPackageEpoch,
            int currentSerial,
            long currentEpoch,
            long currentPackageEpoch) {
        return embeddedSerial >= 0 && embeddedSerial == currentSerial
                && embeddedEpoch != 0L && embeddedEpoch == currentEpoch
                && embeddedPackageEpoch != 0L
                && embeddedPackageEpoch == currentPackageEpoch;
    }
}
