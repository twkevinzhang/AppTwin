package com.lody.virtual.server.pm;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.remote.TrustedPackageProvenance;

/**
 * @author Lody
 */

public class PackageSetting implements Parcelable {

    public static final Parcelable.Creator<PackageSetting> CREATOR = new Parcelable.Creator<PackageSetting>() {
        @Override
        public PackageSetting createFromParcel(Parcel source) {
            return new PackageSetting(source);
        }

        @Override
        public PackageSetting[] newArray(int size) {
            return new PackageSetting[size];
        }
    };
    private static final PackageUserState DEFAULT_USER_STATE = new PackageUserState();
    public String packageName;
    public String apkPath;
    public String libPath;
    public boolean dependSystem;
    @Deprecated
    public boolean skipDexOpt;
    public int appId;
    public long firstInstallTime;
    public long lastUpdateTime;
    public TrustedPackageProvenance trustedPackageProvenance;

    /**
     * Monotonic identity epoch for the private base/split code owned by this setting. A verified
     * revision is useful only while its recorded cheap file facts still match this generation.
     */
    public long packageRevisionGeneration;
    public String verifiedRevisionId;
    public String verifiedBaseSha256;
    public String verifiedBasePath;
    public long verifiedBaseSize;
    public long verifiedBaseLastModified;
    public String[] verifiedSplitNames;
    public String[] verifiedSplitSha256;
    public String[] verifiedSplitPaths;
    public long[] verifiedSplitSizes;
    public long[] verifiedSplitLastModified;

    public String[] splitCodePaths;
    private SparseArray<PackageUserState> userState = new SparseArray<>();

    public PackageSetting() {
    }

    protected PackageSetting(Parcel in) {
        this(in, true, true);
    }

    PackageSetting(Parcel in, boolean hasTrustedProvenance) {
        this(in, hasTrustedProvenance, false);
    }

    PackageSetting(Parcel in, boolean hasTrustedProvenance, boolean hasVerifiedRevision) {
        this.packageName = in.readString();
        this.apkPath = in.readString();
        this.libPath = in.readString();
        this.dependSystem = in.readByte() != 0;
        this.appId = in.readInt();
        //noinspection unchecked
        this.userState = in.readSparseArray(PackageUserState.class.getClassLoader());
        this.skipDexOpt = in.readByte() != 0;
        this.splitCodePaths = in.createStringArray();
        if (hasTrustedProvenance) {
            this.trustedPackageProvenance = in.readParcelable(
                    TrustedPackageProvenance.class.getClassLoader());
        }
        if (hasVerifiedRevision) {
            this.packageRevisionGeneration = in.readLong();
            this.verifiedRevisionId = in.readString();
            this.verifiedBaseSha256 = in.readString();
            this.verifiedBasePath = in.readString();
            this.verifiedBaseSize = in.readLong();
            this.verifiedBaseLastModified = in.readLong();
            this.verifiedSplitNames = in.createStringArray();
            this.verifiedSplitSha256 = in.createStringArray();
            this.verifiedSplitPaths = in.createStringArray();
            this.verifiedSplitSizes = in.createLongArray();
            this.verifiedSplitLastModified = in.createLongArray();
        }
    }

    public InstalledAppInfo getAppInfo() {
        return new InstalledAppInfo(packageName, apkPath, libPath, dependSystem, skipDexOpt, appId, splitCodePaths);
    }

    PackageUserState modifyUserState(int userId) {
        PackageUserState state = userState.get(userId);
        if (state == null) {
            state = new PackageUserState();
            userState.put(userId, state);
        }
        return state;
    }

    void setUserState(int userId, boolean launched, boolean hidden, boolean installed) {
        PackageUserState state = modifyUserState(userId);
        state.launched = launched;
        state.hidden = hidden;
        state.installed = installed;
    }

    PackageUserState readUserState(int userId) {
        PackageUserState state = userState.get(userId);
        if (state != null) {
            return state;
        }
        return DEFAULT_USER_STATE;
    }

    void removeUser(int userId) {
        userState.delete(userId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.packageName);
        dest.writeString(this.apkPath);
        dest.writeString(this.libPath);
        dest.writeByte(this.dependSystem ? (byte) 1 : (byte) 0);
        dest.writeInt(this.appId);
        //noinspection unchecked
        dest.writeSparseArray((SparseArray) this.userState);
        dest.writeByte(this.skipDexOpt ? (byte) 1 : (byte) 0);
        dest.writeStringArray(this.splitCodePaths);
        dest.writeParcelable(this.trustedPackageProvenance, flags);
        dest.writeLong(this.packageRevisionGeneration);
        dest.writeString(this.verifiedRevisionId);
        dest.writeString(this.verifiedBaseSha256);
        dest.writeString(this.verifiedBasePath);
        dest.writeLong(this.verifiedBaseSize);
        dest.writeLong(this.verifiedBaseLastModified);
        dest.writeStringArray(this.verifiedSplitNames);
        dest.writeStringArray(this.verifiedSplitSha256);
        dest.writeStringArray(this.verifiedSplitPaths);
        dest.writeLongArray(this.verifiedSplitSizes);
        dest.writeLongArray(this.verifiedSplitLastModified);
    }

    void invalidateVerifiedRevision() {
        if (packageRevisionGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Package revision generation exhausted");
        }
        packageRevisionGeneration++;
        clearVerifiedRevision();
    }

    void clearVerifiedRevision() {
        verifiedRevisionId = null;
        verifiedBaseSha256 = null;
        verifiedBasePath = null;
        verifiedBaseSize = 0;
        verifiedBaseLastModified = 0;
        verifiedSplitNames = null;
        verifiedSplitSha256 = null;
        verifiedSplitPaths = null;
        verifiedSplitSizes = null;
        verifiedSplitLastModified = null;
    }

    public boolean isLaunched(int userId) {
        return readUserState(userId).launched;
    }

    public boolean isHidden(int userId) {
        return readUserState(userId).hidden;
    }

    public boolean isInstalled(int userId) {
        return readUserState(userId).installed;
    }

    public void setLaunched(int userId, boolean launched) {
        modifyUserState(userId).launched = launched;
    }

    public void setHidden(int userId, boolean hidden) {
        modifyUserState(userId).hidden = hidden;
    }

    public void setInstalled(int userId, boolean installed) {
        modifyUserState(userId).installed = installed;
    }
}
