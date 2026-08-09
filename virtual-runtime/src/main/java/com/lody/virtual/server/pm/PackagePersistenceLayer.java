package com.lody.virtual.server.pm;

import android.os.Parcel;
import android.system.ErrnoException;
import android.system.Os;

import com.lody.virtual.helper.PersistenceLayer;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.server.pm.parser.VPackage;

import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * @author Lody
 */

class PackagePersistenceLayer extends PersistenceLayer {

    private static final char[] MAGIC = {'v', 'p', 'k', 'g'};
    private static final int CURRENT_VERSION = 3;

    private VAppManagerService mService;

    PackagePersistenceLayer(VAppManagerService service) {
        super(VEnvironment.getPackageListFile());
        mService = service;
    }

    File persistenceFile() {
        return getPersistenceFile();
    }

    File pendingFile() {
        File destination = getPersistenceFile();
        return new File(destination.getParentFile(), destination.getName() + ".next");
    }

    void discardPendingWriteOrThrow() throws IOException {
        File pending = pendingFile();
        if (pending.exists() && !pending.delete()) {
            throw new IOException("Unable to discard pending package settings");
        }
        PackageInstallTransaction.syncDirectory(pending.getParentFile());
    }

    @Override
    public int getCurrentVersion() {
        return CURRENT_VERSION;
    }

    @Override
    public void writeMagic(Parcel p) {
        p.writeCharArray(MAGIC);
    }

    @Override
    public boolean verifyMagic(Parcel p) {
        char[] magic = p.createCharArray();
        return Arrays.equals(magic, MAGIC);
    }


    @Override
    public void writePersistenceData(Parcel p) {
        synchronized (PackageCacheManager.PACKAGE_CACHE) {
            p.writeInt(PackageCacheManager.PACKAGE_CACHE.size());
            for (VPackage pkg : PackageCacheManager.PACKAGE_CACHE.values()) {
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                ps.writeToParcel(p, 0);
            }
        }
    }

    /** Writes package settings while allowing an install transaction to observe commit failure. */
    void saveOrThrow() throws IOException {
        Parcel parcel = Parcel.obtain();
        File destination = getPersistenceFile();
        File pending = pendingFile();
        try {
            writeMagic(parcel);
            parcel.writeInt(getCurrentVersion());
            writePersistenceData(parcel);
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(parcel.marshall());
                output.getFD().sync();
            }
            try {
                Os.rename(pending.getAbsolutePath(), destination.getAbsolutePath());
                PackageInstallTransaction.syncDirectory(destination.getParentFile());
            } catch (ErrnoException failure) {
                throw new IOException("Unable to activate package settings", failure);
            }
        } finally {
            pending.delete();
            parcel.recycle();
        }
    }

    @Override
    public void readPersistenceData(Parcel p) {
        int count = p.readInt();
        while (count-- > 0) {
            PackageSetting setting = new PackageSetting(p);
            mService.loadPackage(setting);
        }
    }

    @Override
    public boolean onVersionConflict(int fileVersion, int currentVersion) {
        // I am so lazy to process it...
        return false;
    }

    @Override
    public void onPersistenceFileDamage() {
        getPersistenceFile().delete();
        VAppManagerService.get().restoreFactoryState();
    }
}
