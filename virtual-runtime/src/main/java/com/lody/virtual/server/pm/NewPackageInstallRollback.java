package com.lody.virtual.server.pm;

import com.lody.virtual.helper.utils.FileUtils;

import java.io.File;
import java.io.IOException;

/** Removes every observable artifact when a first install fails before its settings commit. */
final class NewPackageInstallRollback {
    interface Operations {
        boolean exists(File file);

        boolean delete(File file);

        void removePackageCache(String packageName);
    }

    private static final Operations DEFAULT_OPERATIONS = new Operations() {
        @Override
        public boolean exists(File file) {
            return file.exists();
        }

        @Override
        public boolean delete(File file) {
            return FileUtils.deleteDir(file);
        }

        @Override
        public void removePackageCache(String packageName) {
            if (PackageCacheManager.get(packageName) != null) {
                PackageCacheManager.remove(packageName);
            }
        }
    };

    private final String packageName;
    private final File appDirectory;
    private final File externalOdex;
    private final Operations operations;
    private boolean completed;

    static NewPackageInstallRollback begin(
            String packageName, File appDirectory, File odexFile) throws IOException {
        return begin(packageName, appDirectory, odexFile, DEFAULT_OPERATIONS);
    }

    static NewPackageInstallRollback begin(
            String packageName, File appDirectory, File odexFile, Operations operations)
            throws IOException {
        String appPath = appDirectory.getCanonicalPath() + File.separator;
        File externalOdex = odexFile.getCanonicalPath().startsWith(appPath) ? null : odexFile;
        return new NewPackageInstallRollback(
                packageName, appDirectory, externalOdex, operations);
    }

    private NewPackageInstallRollback(
            String packageName, File appDirectory, File externalOdex, Operations operations) {
        this.packageName = packageName;
        this.appDirectory = appDirectory;
        this.externalOdex = externalOdex;
        this.operations = operations;
    }

    void commit() {
        completed = true;
    }

    void rollback() throws IOException {
        if (completed) {
            return;
        }
        operations.removePackageCache(packageName);
        IOException failure = null;
        if (operations.exists(appDirectory) && !operations.delete(appDirectory)) {
            failure = new IOException("Unable to remove failed first install " + appDirectory);
        }
        if (externalOdex != null
                && operations.exists(externalOdex)
                && !operations.delete(externalOdex)) {
            IOException odexFailure = new IOException(
                    "Unable to remove failed first-install odex " + externalOdex);
            if (failure == null) {
                failure = odexFailure;
            } else {
                failure.addSuppressed(odexFailure);
            }
        }
        completed = failure == null;
        if (failure != null) {
            throw failure;
        }
    }
}
