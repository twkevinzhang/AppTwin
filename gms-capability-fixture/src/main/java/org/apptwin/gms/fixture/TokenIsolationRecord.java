package org.apptwin.gms.fixture;

import java.util.Objects;

/** Contains only isolation scope and an optional one-way token fingerprint, never a raw token. */
public final class TokenIsolationRecord {
    public final String packageName;
    public final String groupSentinel;
    public final String senderScope;
    public final String tokenFingerprint;

    public TokenIsolationRecord(String packageName, String groupSentinel, String senderScope,
                                String tokenFingerprint) {
        this.packageName = Objects.requireNonNull(packageName);
        this.groupSentinel = Objects.requireNonNull(groupSentinel);
        this.senderScope = Objects.requireNonNull(senderScope);
        this.tokenFingerprint = Objects.requireNonNull(tokenFingerprint);
    }

    public String isolationKey() {
        return packageName + "|" + groupSentinel + "|" + senderScope;
    }
}
