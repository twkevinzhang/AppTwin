package com.lody.virtual.client;

/** Keeps Java/libcore guest identity separate from the optional process-wide native override. */
final class GuestUidPolicy {

    private GuestUidPolicy() {
    }

    static int guestFacingUid(int baseVUid, int nativeUidOverride, int kernelUid) {
        return nativeUidOverride >= 0 ? nativeUidOverride : kernelUid;
    }
}
