package com.lody.virtual.remote;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable identity asserted by AppTwin's trusted artifact manifest.
 *
 * <p>This object is deliberately not inferred from AndroidManifest metadata. The host-side
 * trusted bundle loader must construct it from an authenticated manifest and pass it through the
 * dedicated trusted install path.</p>
 */
public final class TrustedPackageProvenance implements Parcelable {
    public static final int SCHEMA_VERSION = 1;

    public static final Creator<TrustedPackageProvenance> CREATOR =
            new Creator<TrustedPackageProvenance>() {
                @Override
                public TrustedPackageProvenance createFromParcel(Parcel source) {
                    return new TrustedPackageProvenance(source);
                }

                @Override
                public TrustedPackageProvenance[] newArray(int size) {
                    return new TrustedPackageProvenance[size];
                }
            };

    public final String manifestId;
    public final String packageName;
    public final int versionCode;
    public final List<String> realSignerSha256;
    public final String baseApkSha256;
    public final Map<String, String> splitApkSha256;
    public final byte[] effectiveSignature;

    public TrustedPackageProvenance(
            String manifestId,
            String packageName,
            int versionCode,
            List<String> realSignerSha256,
            String baseApkSha256,
            Map<String, String> splitApkSha256,
            byte[] effectiveSignature) {
        this.manifestId = manifestId;
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.realSignerSha256 = Collections.unmodifiableList(new ArrayList<>(realSignerSha256));
        this.baseApkSha256 = baseApkSha256;
        this.splitApkSha256 = Collections.unmodifiableMap(
                new LinkedHashMap<>(splitApkSha256));
        this.effectiveSignature = effectiveSignature == null
                ? null : effectiveSignature.clone();
    }

    private TrustedPackageProvenance(Parcel source) {
        int schemaVersion = source.readInt();
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported trusted provenance schema: " + schemaVersion);
        }
        manifestId = source.readString();
        packageName = source.readString();
        versionCode = source.readInt();
        ArrayList<String> signers = source.createStringArrayList();
        realSignerSha256 = Collections.unmodifiableList(
                signers == null ? new ArrayList<>() : signers);
        baseApkSha256 = source.readString();
        Bundle splits = source.readBundle(TrustedPackageProvenance.class.getClassLoader());
        LinkedHashMap<String, String> splitMap = new LinkedHashMap<>();
        if (splits != null) {
            for (String splitName : splits.keySet()) {
                splitMap.put(splitName, splits.getString(splitName));
            }
        }
        splitApkSha256 = Collections.unmodifiableMap(splitMap);
        effectiveSignature = source.createByteArray();
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(SCHEMA_VERSION);
        destination.writeString(manifestId);
        destination.writeString(packageName);
        destination.writeInt(versionCode);
        destination.writeStringList(realSignerSha256);
        destination.writeString(baseApkSha256);
        Bundle splits = new Bundle();
        for (Map.Entry<String, String> split : splitApkSha256.entrySet()) {
            splits.putString(split.getKey(), split.getValue());
        }
        destination.writeBundle(splits);
        destination.writeByteArray(effectiveSignature);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
