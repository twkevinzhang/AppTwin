package com.lody.virtual.remote;

import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

/** Result of applying virtual task policy before the host performs Android UI work. */
public final class PreparedActivityLaunch implements Parcelable {
    public static final int DISPOSITION_FAILURE = 0;
    public static final int DISPOSITION_REUSED = 1;
    public static final int DISPOSITION_HOST_START_REQUIRED = 2;

    public static final Creator<PreparedActivityLaunch> CREATOR =
            new Creator<PreparedActivityLaunch>() {
                @Override
                public PreparedActivityLaunch createFromParcel(Parcel source) {
                    return new PreparedActivityLaunch(source);
                }

                @Override
                public PreparedActivityLaunch[] newArray(int size) {
                    return new PreparedActivityLaunch[size];
                }
            };

    private final int disposition;
    private final Intent intent;
    private final String failureReason;
    private final String launchId;
    private final int taskId;

    private PreparedActivityLaunch(
            int disposition, Intent intent, String failureReason, String launchId, int taskId) {
        if (disposition == DISPOSITION_FAILURE) {
            if (intent != null || launchId != null || taskId != -1) {
                throw new IllegalArgumentException("Failed launch must not contain host work");
            }
        } else if (disposition == DISPOSITION_REUSED) {
            if (intent != null || failureReason != null || launchId == null
                    || launchId.isEmpty() || taskId < 0) {
                throw new IllegalArgumentException("Reused launch requires a task and launch id");
            }
        } else if (disposition == DISPOSITION_HOST_START_REQUIRED) {
            if (intent == null || launchId == null || launchId.isEmpty()
                    || failureReason != null || taskId != -1) {
                throw new IllegalArgumentException("Host launch requires an Intent and launch id");
            }
        } else {
            throw new IllegalArgumentException("Unknown launch disposition: " + disposition);
        }
        this.disposition = disposition;
        this.intent = intent == null ? null : new Intent(intent);
        this.failureReason = failureReason;
        this.launchId = launchId;
        this.taskId = taskId;
    }

    private PreparedActivityLaunch(Parcel source) {
        this(source.readInt(),
                source.readParcelable(Intent.class.getClassLoader()),
                source.readString(), source.readString(), source.readInt());
    }

    public static PreparedActivityLaunch reused(int taskId, String launchId) {
        return new PreparedActivityLaunch(DISPOSITION_REUSED, null, null, launchId, taskId);
    }

    public static PreparedActivityLaunch hostStartRequired(Intent intent, String launchId) {
        return new PreparedActivityLaunch(
                DISPOSITION_HOST_START_REQUIRED, intent, null, launchId, -1);
    }

    public static PreparedActivityLaunch failure(String reason) {
        return new PreparedActivityLaunch(DISPOSITION_FAILURE, null, reason, null, -1);
    }

    public int getDisposition() { return disposition; }

    public boolean isSuccess() { return disposition != DISPOSITION_FAILURE; }

    public boolean isReused() { return disposition == DISPOSITION_REUSED; }

    public boolean isHostStartRequired() {
        return disposition == DISPOSITION_HOST_START_REQUIRED;
    }

    public Intent getIntent() { return intent == null ? null : new Intent(intent); }

    public String getFailureReason() { return failureReason; }

    public String getLaunchId() { return launchId; }

    public int getTaskId() { return taskId; }

    public PreparedActivityLaunch copy() {
        return new PreparedActivityLaunch(disposition, intent, failureReason, launchId, taskId);
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(disposition);
        destination.writeParcelable(intent, flags);
        destination.writeString(failureReason);
        destination.writeString(launchId);
        destination.writeInt(taskId);
    }

    @Override
    public int describeContents() { return 0; }
}
