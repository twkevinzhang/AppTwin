package com.lody.virtual.remote;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Sanitized, per-virtual-user Cloud Messaging lifecycle state.
 *
 * <p>This object deliberately carries no registration token, account identifier, message
 * payload, or socket detail. It is safe for the host UI to use as a health signal.</p>
 */
public final class TrustedGmsCloudMessagingState implements Parcelable {
    public static final int PHASE_DISABLED = 0;
    public static final int PHASE_STARTING = 1;
    public static final int PHASE_CONNECTED = 2;
    public static final int PHASE_DEGRADED = 3;
    public static final int PHASE_UNKNOWN = 4;

    public final int phase;
    public final boolean processAlive;
    public final boolean bindingAlive;
    public final long lastConnectedAtMillis;
    public final int retryAttempt;
    public final long generation;
    public final String failureCode;

    public TrustedGmsCloudMessagingState(int phase, boolean processAlive,
            boolean bindingAlive, long lastConnectedAtMillis, int retryAttempt,
            long generation, String failureCode) {
        this.phase = phase;
        this.processAlive = processAlive;
        this.bindingAlive = bindingAlive;
        this.lastConnectedAtMillis = lastConnectedAtMillis;
        this.retryAttempt = retryAttempt;
        this.generation = generation;
        this.failureCode = failureCode;
    }

    private TrustedGmsCloudMessagingState(Parcel source) {
        phase = source.readInt();
        processAlive = source.readInt() != 0;
        bindingAlive = source.readInt() != 0;
        lastConnectedAtMillis = source.readLong();
        retryAttempt = source.readInt();
        generation = source.readLong();
        failureCode = source.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(phase);
        dest.writeInt(processAlive ? 1 : 0);
        dest.writeInt(bindingAlive ? 1 : 0);
        dest.writeLong(lastConnectedAtMillis);
        dest.writeInt(retryAttempt);
        dest.writeLong(generation);
        dest.writeString(failureCode);
    }

    public static final Creator<TrustedGmsCloudMessagingState> CREATOR =
            new Creator<TrustedGmsCloudMessagingState>() {
                @Override
                public TrustedGmsCloudMessagingState createFromParcel(Parcel source) {
                    return new TrustedGmsCloudMessagingState(source);
                }

                @Override
                public TrustedGmsCloudMessagingState[] newArray(int size) {
                    return new TrustedGmsCloudMessagingState[size];
                }
            };
}
