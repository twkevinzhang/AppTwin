package com.lody.virtual.remote;

import android.content.Intent;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.IOException;

/**
 * Guest files opened read-only by the guest itself, handed to the host bridge.
 * {@link #writeToParcel} forwards the caller's flags so an AIDL return value closes the
 * server's descriptors once they are sent.
 */
public final class OutboundShareRequest implements Parcelable {
    public final Intent intent;
    public final ParcelFileDescriptor[] sources;
    public final String[] names;
    public final String[] mimes;
    public final String[] uris;
    public final boolean chooser;
    public int userId;

    public OutboundShareRequest(Intent intent, ParcelFileDescriptor[] sources, String[] names,
                                String[] mimes, String[] uris, boolean chooser, int userId) {
        this.intent = intent;
        this.sources = sources;
        this.names = names;
        this.mimes = mimes;
        this.uris = uris;
        this.chooser = chooser;
        this.userId = userId;
    }

    private OutboundShareRequest(Parcel in) {
        intent = in.readParcelable(Intent.class.getClassLoader());
        int count = in.readInt();
        sources = new ParcelFileDescriptor[count];
        for (int i = 0; i < count; i++) {
            sources[i] = in.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(in) : null;
        }
        names = in.createStringArray();
        mimes = in.createStringArray();
        uris = in.createStringArray();
        chooser = in.readInt() != 0;
        userId = in.readInt();
    }

    public boolean isWellFormed() {
        if (intent == null || sources == null || names == null || mimes == null || uris == null) return false;
        int n = sources.length;
        if (n == 0 || names.length != n || mimes.length != n || uris.length != n) return false;
        for (ParcelFileDescriptor source : sources) if (source == null) return false;
        return true;
    }

    public void closeSources() {
        if (sources == null) return;
        for (ParcelFileDescriptor source : sources) {
            if (source == null) continue;
            try { source.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(intent, 0);
        dest.writeInt(sources == null ? 0 : sources.length);
        if (sources != null) for (ParcelFileDescriptor source : sources) {
            dest.writeInt(source != null ? 1 : 0);
            if (source != null) source.writeToParcel(dest, flags);
        }
        dest.writeStringArray(names);
        dest.writeStringArray(mimes);
        dest.writeStringArray(uris);
        dest.writeInt(chooser ? 1 : 0);
        dest.writeInt(userId);
    }

    @Override public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }

    public static final Creator<OutboundShareRequest> CREATOR = new Creator<OutboundShareRequest>() {
        @Override public OutboundShareRequest createFromParcel(Parcel in) { return new OutboundShareRequest(in); }
        @Override public OutboundShareRequest[] newArray(int size) { return new OutboundShareRequest[size]; }
    };
}
