package com.lody.virtual.helper;

import android.os.Parcel;

import com.lody.virtual.helper.utils.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * @author Lody
 */
public abstract class PersistenceLayer {

    private File mPersistenceFile;

    public PersistenceLayer(File persistenceFile) {
        this.mPersistenceFile = persistenceFile;
    }

    public final File getPersistenceFile() {
        return mPersistenceFile;
    }

    public abstract int getCurrentVersion();

    public void writeMagic(Parcel p) {
    }

    public boolean verifyMagic(Parcel p) {
        return true;
    }

    public abstract void writePersistenceData(Parcel p);

    public abstract void readPersistenceData(Parcel p);

    public boolean onVersionConflict(int fileVersion, int currentVersion) {
        return false;
    }

    public void onPersistenceFileDamage() {
    }

    public void save() {
        try {
            saveAtomicallyOrThrow();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Atomically persists state and reports failure to privacy-sensitive cleanup callers. */
    public void saveAtomicallyOrThrow() throws IOException {
        Parcel p = Parcel.obtain();
        AtomicFile atomicFile = new AtomicFile(mPersistenceFile);
        FileOutputStream fos = null;
        try {
            writeMagic(p);
            p.writeInt(getCurrentVersion());
            writePersistenceData(p);
            fos = atomicFile.startWrite();
            fos.write(p.marshall());
            atomicFile.finishWrite(fos);
            fos = null;
        } catch (Exception e) {
            atomicFile.failWrite(fos);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Unable to persist virtual runtime state", e);
        } finally {
            p.recycle();
        }
    }

    public void read() {
        File file = mPersistenceFile;
        Parcel p = Parcel.obtain();
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            int len = fis.read(bytes);
            fis.close();
            if (len != bytes.length) {
                throw new IOException("Unable to read Persistence file.");
            }
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            if (!verifyMagic(p)) {
                onPersistenceFileDamage();
                throw new IOException("Invalid persistence file.");
            }
            int fileVersion = p.readInt();
            int currentVersion = getCurrentVersion();
            if (fileVersion != getCurrentVersion()) {
                if (!onVersionConflict(fileVersion, currentVersion)) {
                    throw new IOException("Unable to process the bad version persistence file.");
                }
            }
            readPersistenceData(p);
        } catch (Exception e) {
            if (!(e instanceof FileNotFoundException)) {
                e.printStackTrace();
            }
        } finally {
            p.recycle();
        }
    }
}
