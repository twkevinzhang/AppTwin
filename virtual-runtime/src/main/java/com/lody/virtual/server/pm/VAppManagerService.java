package com.lody.virtual.server.pm;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import com.lody.virtual.client.core.InstallStrategy;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.collection.IntArray;
import com.lody.virtual.helper.compat.NativeLibraryHelperCompat;
import com.lody.virtual.helper.utils.ArrayUtils;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.InstallResult;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.IAppManager;
import com.lody.virtual.server.accounts.VAccountManagerService;
import com.lody.virtual.server.am.BroadcastSystem;
import com.lody.virtual.server.am.UidSystem;
import com.lody.virtual.server.am.VActivityManagerService;
import com.lody.virtual.server.interfaces.IAppRequestListener;
import com.lody.virtual.server.interfaces.IPackageObserver;
import com.lody.virtual.server.pm.parser.PackageParserEx;
import com.lody.virtual.server.pm.parser.VPackage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Lody
 */
public class VAppManagerService extends IAppManager.Stub {

    private static final String TAG = VAppManagerService.class.getSimpleName();
    private static final AtomicReference<VAppManagerService> sService = new AtomicReference<>();
    private final UidSystem mUidSystem = new UidSystem();
    private final PackagePersistenceLayer mPersistenceLayer = new PackagePersistenceLayer(this);
    private final Set<String> mVisibleOutsidePackages = new HashSet<>();
    private boolean mBooting;
    private RemoteCallbackList<IPackageObserver> mRemoteCallbackList = new RemoteCallbackList<>();
    private IAppRequestListener mAppRequestListener;

    public static VAppManagerService get() {
        return sService.get();
    }

    public static void systemReady() {
        VEnvironment.systemReady();
        VAppManagerService instance = new VAppManagerService();
        try {
            instance.mPersistenceLayer.discardPendingWriteOrThrow();
            PackageInstallTransaction.recover(
                    instance.packageTransactionJournal(), instance.packageTransactionRoot());
        } catch (IOException recoveryFailure) {
            throw new IllegalStateException(
                    "Unable to recover interrupted package install", recoveryFailure);
        }
        instance.mUidSystem.initUidList();
        sService.set(instance);
    }

    public boolean isBooting() {
        return mBooting;
    }

    @Override
    public void scanApps() {
        if (mBooting) {
            return;
        }
        synchronized (this) {
            mBooting = true;
            try {
                mPersistenceLayer.read();
                PrivilegeAppOptimizer.get().performOptimizeAllApps();
            } finally {
                mBooting = false;
            }
        }
    }

    private void cleanUpResidualFiles(PackageSetting ps) {
        VLog.w(TAG, "cleanUpResidualFiles: " + ps.packageName);
        File dataAppDir = VEnvironment.getDataAppPackageDirectory(ps.packageName);
        FileUtils.deleteDir(dataAppDir);

        // We shouldn't remove user data here!!! Just remove the package.
        // for (int userId : VUserManagerService.get().getUserIds()) {
        //     FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, ps.packageName));
        // }
    }


    synchronized void loadPackage(PackageSetting setting) {
        if (!loadPackageInnerLocked(setting)) {
            cleanUpResidualFiles(setting);
        }
    }

    private boolean loadPackageInnerLocked(PackageSetting ps) {
        if (ps.dependSystem) {
            if (!VirtualCore.get().isOutsideInstalled(ps.packageName)) {
                return false;
            }
        }
        File cacheFile = VEnvironment.getPackageCacheFile(ps.packageName);
        VPackage pkg = null;
        try {
            pkg = PackageParserEx.readPackageCache(ps.packageName);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        if (pkg == null || pkg.packageName == null) {
            return false;
        }
        chmodPackageDictionary(cacheFile);
        PackageCacheManager.put(pkg, ps);
        BroadcastSystem.get().startApp(pkg);
        return true;
    }

    @Override
    public boolean isOutsidePackageVisible(String pkg) {
        return pkg != null && mVisibleOutsidePackages.contains(pkg);
    }

    @Override
    public void addVisibleOutsidePackage(String pkg) {
        if (pkg != null) {
            mVisibleOutsidePackages.add(pkg);
        }
    }

    @Override
    public void removeVisibleOutsidePackage(String pkg) {
        if (pkg != null) {
            mVisibleOutsidePackages.remove(pkg);
        }
    }

    @Override
    public InstallResult installPackage(String path, int flags) {
        return installPackage(path, flags, true);
    }

    public synchronized InstallResult installPackage(String path, int flags, boolean notify) {
        return installPackageTransactional(
                path, flags, notify, PackageInstallScope.GLOBAL_USER_ID);
    }

    /**
     * Installs or updates shared package code and exposes it only to the requested virtual user.
     * Existing users keep their installed state when the shared code is updated.
     */
    public synchronized InstallResult installPackageForUser(String path, int flags, int userId) {
        if (!VUserManagerService.get().exists(userId)) {
            return InstallResult.makeFailure("User " + userId + " does not exist.");
        }

        return installPackageTransactional(path, flags, true, userId);
    }

    private InstallResult installPackageTransactional(String path, int flags, boolean notify,
                                                       int requestedUserId) {
        VPackage stagedPackage = parseStagedPackage(path);
        VPackage existingPackage = stagedPackage == null
                ? null : PackageCacheManager.get(stagedPackage.packageName);
        boolean equalVersionUserBinding = existingPackage != null
                && PackageInstallScope.isUserScoped(requestedUserId)
                && existingPackage.mVersionCode == stagedPackage.mVersionCode;
        boolean requestedUserAlreadyInstalled = equalVersionUserBinding
                && ((PackageSetting) existingPackage.mExtras).isInstalled(requestedUserId);
        PackageSettingSnapshot settingSnapshot = existingPackage == null
                ? null : new PackageSettingSnapshot(
                        (PackageSetting) existingPackage.mExtras,
                        VUserManagerService.get().getUserIds());
        PackageInstallTransaction installTransaction = null;
        NewPackageInstallRollback newInstallRollback = null;
        try {
            List<File> snapshotTargets = new ArrayList<>();
            snapshotTargets.add(mPersistenceLayer.persistenceFile());
            List<File> cleanupTargets = new ArrayList<>();
            if (existingPackage != null) {
                if (PackageInstallScope.requiresCodeSnapshot(
                        existingPackage.mVersionCode,
                        stagedPackage.mVersionCode,
                        requestedUserId)) {
                    File appDir = VEnvironment.getDataAppPackageDirectory(
                            existingPackage.packageName);
                    File odexFile = VEnvironment.getOdexFile(existingPackage.packageName);
                    snapshotTargets.add(appDir);
                    if (isOutsideDirectory(odexFile, appDir)) {
                        snapshotTargets.add(odexFile);
                    }
                }
            } else if (existingPackage == null && stagedPackage != null) {
                File appDir = new File(
                        VEnvironment.getDataAppDirectory(), stagedPackage.packageName);
                File odexFile = VEnvironment.getOdexFile(stagedPackage.packageName);
                cleanupTargets.add(appDir);
                if (isOutsideDirectory(odexFile, appDir)) cleanupTargets.add(odexFile);
                newInstallRollback = NewPackageInstallRollback.begin(
                        stagedPackage.packageName,
                        appDir,
                        odexFile);
            } else {
                snapshotTargets.clear();
            }
            if (!snapshotTargets.isEmpty() || !cleanupTargets.isEmpty()) {
                installTransaction = PackageInstallTransaction.begin(
                        packageTransactionJournal(), packageTransactionRoot(),
                        snapshotTargets, cleanupTargets);
            }

            InstallResult result = installPackageInternal(path, flags, requestedUserId);
            if (result.isSuccess) {
                if (installTransaction != null) {
                    installTransaction.commit();
                }
                if (newInstallRollback != null) {
                    newInstallRollback.commit();
                }
                completeCommittedInstall(
                        result, notify, requestedUserId,
                        equalVersionUserBinding, requestedUserAlreadyInstalled);
                return result;
            }
            restoreFailedInstall(
                    installTransaction, newInstallRollback, existingPackage, settingSnapshot);
            return result;
        } catch (Throwable failure) {
            try {
                restoreFailedInstall(
                        installTransaction, newInstallRollback, existingPackage, settingSnapshot);
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            VLog.e(TAG, "Package install transaction failed: %s", failure.getMessage());
            VLog.e(TAG, failure);
            return InstallResult.makeFailure("Install transaction failed: " + failure.getMessage());
        }
    }

    /** Runs externally visible effects only after the durable transaction marker is committed. */
    private void completeCommittedInstall(
            InstallResult result,
            boolean notify,
            int requestedUserId,
            boolean equalVersionUserBinding,
            boolean requestedUserAlreadyInstalled) {
        try {
            VPackage installedPackage = PackageCacheManager.get(result.packageName);
            if (!equalVersionUserBinding && installedPackage != null) {
                BroadcastSystem.get().startApp(installedPackage);
            }
            if (notify && !(equalVersionUserBinding && requestedUserAlreadyInstalled)) {
                PackageSetting setting = PackageCacheManager.getSetting(result.packageName);
                if (setting != null) {
                    notifyAppInstalled(
                            setting, PackageInstallScope.notificationUserId(requestedUserId));
                }
            }
        } catch (Throwable sideEffectFailure) {
            // The package/settings commit is already durable. A best-effort runtime activation or
            // observer failure must not report the committed install as failed and trigger rollback.
            VLog.e(TAG, "Post-commit package activation failed: %s",
                    sideEffectFailure.getMessage());
            VLog.e(TAG, sideEffectFailure);
        }
    }

    private VPackage parseStagedPackage(String path) {
        if (path == null) {
            return null;
        }
        try {
            return PackageParserEx.parsePackage(new File(path));
        } catch (Throwable ignored) {
            // The normal install path will return the canonical parse error.
            return null;
        }
    }

    private static boolean isOutsideDirectory(File file, File directory) throws IOException {
        String filePath = file.getCanonicalPath();
        String directoryPath = directory.getCanonicalPath() + File.separator;
        return !filePath.startsWith(directoryPath);
    }

    private void restoreFailedInstall(PackageInstallTransaction installTransaction,
                                      NewPackageInstallRollback newInstallRollback,
                                      VPackage existingPackage,
                                      PackageSettingSnapshot settingSnapshot)
            throws IOException {
        if (newInstallRollback != null) {
            IOException rollbackFailure = null;
            if (installTransaction != null) {
                try {
                    installTransaction.rollback();
                } catch (IOException failure) {
                    rollbackFailure = failure;
                }
            }
            try {
                newInstallRollback.rollback();
            } catch (IOException failure) {
                if (rollbackFailure == null) {
                    rollbackFailure = failure;
                } else {
                    rollbackFailure.addSuppressed(failure);
                }
            }
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
            return;
        }
        if (existingPackage == null || settingSnapshot == null) {
            return;
        }
        if (installTransaction != null) installTransaction.rollback();
        PackageSetting setting = (PackageSetting) existingPackage.mExtras;
        settingSnapshot.restore(setting);
        boolean cacheChanged = PackageCacheManager.get(existingPackage.packageName) != existingPackage;
        if (cacheChanged) {
            BroadcastSystem.get().stopApp(existingPackage.packageName);
            PackageCacheManager.remove(existingPackage.packageName);
            PackageCacheManager.put(existingPackage, setting);
            BroadcastSystem.get().startApp(existingPackage);
        }
    }

    private InstallResult installPackageInternal(String path, int flags, int requestedUserId) {
        long installTime = System.currentTimeMillis();
        if (path == null) {
            return InstallResult.makeFailure("path = NULL");
        }
        File packageFile = new File(path);
        if (!packageFile.exists()) {
            return InstallResult.makeFailure("Package File is not exist.");
        }
        VPackage pkg = null;
        try {
            pkg = PackageParserEx.parsePackage(packageFile);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        if (pkg == null || pkg.packageName == null) {
            return InstallResult.makeFailure("Unable to parse the package.");
        }
        InstallResult res = new InstallResult();
        res.packageName = pkg.packageName;
        // PackageCache holds all packages, try to check if we need to update.
        VPackage existOne = PackageCacheManager.get(pkg.packageName);
        PackageSetting existSetting = existOne != null ? (PackageSetting) existOne.mExtras : null;
        PackageInstalledStateSnapshot installedStateBeforeUpdate = null;
        if (existOne != null) {
            if (!PackageSignaturePolicy.isCompatible(existOne.mSignatures, pkg.mSignatures)) {
                return InstallResult.makeFailure(
                        "Can not update the package because its signing certificates differ.");
            }
            // A second user installing the exact shared revision only needs a user binding. Avoid
            // rewriting code (and rejecting an equal version) for that common Play Store flow.
            if (PackageInstallScope.isUserScoped(requestedUserId)
                    && existOne.mVersionCode == pkg.mVersionCode) {
                try {
                    EqualVersionUserBinding.bind(
                            installedStateAccessor(existSetting),
                            requestedUserId,
                            mPersistenceLayer::saveOrThrow);
                } catch (IOException commitFailure) {
                    return InstallResult.makeFailure(
                            "Unable to commit user package binding: "
                                    + commitFailure.getMessage());
                }
                InstallResult existingResult = new InstallResult();
                existingResult.packageName = pkg.packageName;
                existingResult.isSuccess = true;
                existingResult.isUpdate = true;
                return existingResult;
            }
            if ((flags & InstallStrategy.IGNORE_NEW_VERSION) != 0) {
                res.isUpdate = true;
                return res;
            }
            if (!canUpdate(existOne, pkg, flags)) {
                return InstallResult.makeFailure("Can not update the package (such as version downrange).");
            }
            res.isUpdate = true;
            installedStateBeforeUpdate = PackageInstalledStateSnapshot.capture(
                    VUserManagerService.get().getUserIds(), installedStateAccessor(existSetting));
        }
        File appDir = VEnvironment.getDataAppPackageDirectory(pkg.packageName);
        File libDir = new File(appDir, "lib");
        if (res.isUpdate) {
            FileUtils.deleteDir(libDir);
            VEnvironment.getOdexFile(pkg.packageName).delete();
            VActivityManagerService.get().killAppByPkg(pkg.packageName, VUserHandle.USER_ALL);
        }
        if (!libDir.exists() && !libDir.mkdirs()) {
            return InstallResult.makeFailure("Unable to create lib dir.");
        }
        boolean dependSystem = (flags & InstallStrategy.DEPEND_SYSTEM_IF_EXIST) != 0
                && VirtualCore.get().isOutsideInstalled(pkg.packageName);

        if (existSetting != null && existSetting.dependSystem) {
            dependSystem = false;
        }

        String[] splitCodePaths = null;

        if (!dependSystem) {
            File privatePackageFile = new File(appDir, "base.apk");
            File parentFolder = privatePackageFile.getParentFile();
            if (!parentFolder.exists() && !parentFolder.mkdirs()) {
                VLog.w(TAG, "Warning: unable to create folder : " + privatePackageFile.getPath());
            } else if (privatePackageFile.exists() && !privatePackageFile.delete()) {
                VLog.w(TAG, "Warning: unable to delete file : " + privatePackageFile.getPath());
            }
            File baseApkFile = packageFile.isFile() ? packageFile : new File(pkg.baseCodePath);
            try {
                FileUtils.copyFile(baseApkFile, privatePackageFile);
            } catch (IOException e) {
                privatePackageFile.delete();
                return InstallResult.makeFailure("Unable to copy the package file.");
            }
            // copy lib in base apk
            if (NativeLibraryHelperCompat.copyNativeBinaries(baseApkFile, libDir) < 0) {
                privatePackageFile.delete();
                return InstallResult.makeFailure("Unable to extract native libraries from base APK.");
            }

            packageFile = privatePackageFile;

            if (pkg.splitNames != null) {
                int length = pkg.splitNames.length;
                splitCodePaths = new String[length];

                for (int i = 0; i < length; i++) {
                    String splitName = pkg.splitNames[i];
                    File privateSplitFile = new File(appDir, splitName + ".apk");
                    try {
                        FileUtils.copyFile(new File(pkg.splitCodePaths[i]), privateSplitFile);

                        // copy lib in split apk
                        if (NativeLibraryHelperCompat.copyNativeBinaries(
                                privateSplitFile, libDir) < 0) {
                            privateSplitFile.delete();
                            return InstallResult.makeFailure(
                                    "Unable to extract native libraries from split: " + splitName);
                        }
                    } catch (IOException e) {
                        privateSplitFile.delete();
                        return InstallResult.makeFailure("Unable to copy split: " + splitName);
                    }
                    splitCodePaths[i] = privateSplitFile.getPath();
                }
            }
        }

        if (existOne != null) {
            PackageCacheManager.remove(pkg.packageName);
        }
        chmodPackageDictionary(packageFile);
        PackageSetting ps;
        if (existSetting != null) {
            ps = existSetting;
        } else {
            ps = new PackageSetting();
        }
        ps.dependSystem = dependSystem;
        ps.apkPath = packageFile.getPath();
        ps.libPath = libDir.getPath();
        ps.packageName = pkg.packageName;
        ps.appId = VUserHandle.getAppId(mUidSystem.getOrCreateUid(pkg));
        if (res.isUpdate) {
            ps.lastUpdateTime = installTime;
        } else {
            ps.firstInstallTime = installTime;
            ps.lastUpdateTime = installTime;
            for (int userId : VUserManagerService.get().getUserIds()) {
                boolean installed = PackageInstallScope.isInstalledOnFirstInstall(
                        userId, requestedUserId);
                ps.setUserState(userId, false/*launched*/, false/*hidden*/, installed);
            }
        }
        if (installedStateBeforeUpdate != null) {
            // Updating shared code must not rewrite another virtual user's binding. Reapply the
            // full pre-update state explicitly, then expose the revision to the requesting user.
            installedStateBeforeUpdate.restoreAfterSuccessfulUpdate(
                    installedStateAccessor(ps), requestedUserId);
        } else if (PackageInstallScope.isUserScoped(requestedUserId)) {
            // This happens after all code copying/parsing succeeds, so a failed commit cannot make
            // the package visible to the initiating user.
            ps.setInstalled(requestedUserId, true);
        }
        ps.splitCodePaths = splitCodePaths;
        PackageParserEx.savePackageCache(pkg);
        PackageCacheManager.put(pkg, ps);
        try {
            mPersistenceLayer.saveOrThrow();
        } catch (IOException commitFailure) {
            return InstallResult.makeFailure(
                    "Unable to commit package settings: " + commitFailure.getMessage());
        }
        res.isSuccess = true;
        return res;
    }


    @Override
    public synchronized boolean installPackageAsUser(int userId, String packageName) {
        if (VUserManagerService.get().exists(userId)) {
            PackageSetting ps = PackageCacheManager.getSetting(packageName);
            if (ps != null) {
                if (!ps.isInstalled(userId)) {
                    if (!ensureNativeLibraries(ps)) {
                        VLog.e(TAG, "Unable to repair native libraries before adding %s to user %d",
                                packageName, userId);
                        return false;
                    }
                    try {
                        EqualVersionUserBinding.bind(
                                installedStateAccessor(ps),
                                userId,
                                mPersistenceLayer::saveOrThrow);
                    } catch (IOException commitFailure) {
                        VLog.e(TAG, "Unable to persist package binding for user %d: %s",
                                userId, commitFailure.getMessage());
                        return false;
                    }
                    notifyAppInstalled(ps, userId);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ensureNativeLibraries(PackageSetting setting) {
        if (setting.dependSystem) {
            return true;
        }
        File libraryDirectory = new File(setting.libPath);
        if (NativeLibraryHelperCompat.copyNativeBinaries(
                new File(setting.apkPath), libraryDirectory) < 0) {
            return false;
        }
        if (setting.splitCodePaths != null) {
            for (String splitCodePath : setting.splitCodePaths) {
                if (NativeLibraryHelperCompat.copyNativeBinaries(
                        new File(splitCodePath), libraryDirectory) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private void chmodPackageDictionary(File packageFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (FileUtils.isSymlink(packageFile)) {
                    return;
                }
                FileUtils.chmod(packageFile.getParentFile().getAbsolutePath(), FileUtils.FileMode.MODE_755);
                FileUtils.chmod(packageFile.getAbsolutePath(), FileUtils.FileMode.MODE_755);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean canUpdate(VPackage existOne, VPackage newOne, int flags) {
        if ((flags & InstallStrategy.COMPARE_VERSION) != 0) {
            if (existOne.mVersionCode < newOne.mVersionCode) {
                return true;
            }
        }
        if ((flags & InstallStrategy.TERMINATE_IF_EXIST) != 0) {
            return false;
        }
        if ((flags & InstallStrategy.UPDATE_IF_EXIST) != 0) {
            return true;
        }
        return false;
    }


    @Override
    public synchronized boolean uninstallPackage(String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            uninstallPackageFully(ps);
            return true;
        }
        return false;
    }

    @Override
    public boolean clearPackageAsUser(int userId, String packageName) throws RemoteException {
        if (!VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            int[] userIds = getPackageInstalledUsers(packageName);
            if (!ArrayUtils.contains(userIds, userId)) {
                return false;
            }
            if (userIds.length == 1) {
                clearPackage(packageName);
            } else {
                // Just hidden it
                VActivityManagerService.get().killAppByPkg(packageName, userId);
                ps.setInstalled(userId, false);
                mPersistenceLayer.save();
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, packageName));
                FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(userId, packageName));
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(userId, packageName));
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean clearPackage(String packageName) throws RemoteException {
        try {
            BroadcastSystem.get().stopApp(packageName);
            VActivityManagerService.get().killAppByPkg(packageName, VUserHandle.USER_ALL);

            for (int id : VUserManagerService.get().getUserIds()) {
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(id, packageName));
                FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(id, packageName));
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(id, packageName));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public synchronized boolean uninstallPackageAsUser(String packageName, int userId) {
        if (!VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            int[] userIds = getPackageInstalledUsers(packageName);
            if (!ArrayUtils.contains(userIds, userId)) {
                return false;
            }
            // User-scoped uninstall only removes the binding and private data. Shared code remains
            // available as a revision cache even when this was the last installed user.
            VActivityManagerService.get().killAppByPkg(packageName, userId);
            try {
                EqualVersionUserBinding.unbind(
                        installedStateAccessor(ps),
                        userId,
                        mPersistenceLayer::saveOrThrow);
            } catch (IOException commitFailure) {
                VLog.e(TAG, "Unable to persist package removal for user %d: %s",
                        userId, commitFailure.getMessage());
                return false;
            }
            notifyAppUninstalled(ps, userId);
            FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, packageName));
            FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(userId, packageName));
            FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(userId, packageName));
            return true;
        }
        return false;
    }

    private static final class PackageSettingSnapshot {
        private final String apkPath;
        private final String libPath;
        private final boolean dependSystem;
        private final int appId;
        private final long firstInstallTime;
        private final long lastUpdateTime;
        private final String[] splitCodePaths;
        private final PackageInstalledStateSnapshot installedStateSnapshot;

        PackageSettingSnapshot(final PackageSetting setting, int[] activeUserIds) {
            apkPath = setting.apkPath;
            libPath = setting.libPath;
            dependSystem = setting.dependSystem;
            appId = setting.appId;
            firstInstallTime = setting.firstInstallTime;
            lastUpdateTime = setting.lastUpdateTime;
            splitCodePaths = setting.splitCodePaths == null ? null : setting.splitCodePaths.clone();
            installedStateSnapshot = PackageInstalledStateSnapshot.capture(
                    activeUserIds, VAppManagerService.installedStateAccessor(setting));
        }

        void restore(PackageSetting setting) {
            setting.apkPath = apkPath;
            setting.libPath = libPath;
            setting.dependSystem = dependSystem;
            setting.appId = appId;
            setting.firstInstallTime = firstInstallTime;
            setting.lastUpdateTime = lastUpdateTime;
            setting.splitCodePaths = splitCodePaths == null ? null : splitCodePaths.clone();
            installedStateSnapshot.restore(VAppManagerService.installedStateAccessor(setting));
        }
    }

    private static PackageInstalledStateSnapshot.Accessor installedStateAccessor(
            final PackageSetting setting) {
        return new PackageInstalledStateSnapshot.Accessor() {
            @Override
            public boolean isInstalled(int userId) {
                return setting.isInstalled(userId);
            }

            @Override
            public void setInstalled(int userId, boolean installed) {
                setting.setInstalled(userId, installed);
            }
        };
    }

    private File packageTransactionJournal() {
        return new File(
                mPersistenceLayer.persistenceFile().getParentFile(),
                "package-install-transaction");
    }

    private File packageTransactionRoot() {
        return VEnvironment.getDataDirectory().getParentFile();
    }

    private void uninstallPackageFully(PackageSetting ps) {
        String packageName = ps.packageName;
        try {
            BroadcastSystem.get().stopApp(packageName);
            VActivityManagerService.get().killAppByPkg(packageName, VUserHandle.USER_ALL);
            VEnvironment.getPackageResourcePath(packageName).delete();
            FileUtils.deleteDir(VEnvironment.getDataAppPackageDirectory(packageName));
            VEnvironment.getOdexFile(packageName).delete();
            for (int id : VUserManagerService.get().getUserIds()) {
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(id, packageName));
                FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(id, packageName));
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(id, packageName));
            }
            PackageCacheManager.remove(packageName);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            notifyAppUninstalled(ps, -1);
        }
    }

    @Override
    public int[] getPackageInstalledUsers(String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            IntArray installedUsers = new IntArray(5);
            int[] userIds = VUserManagerService.get().getUserIds();
            for (int userId : userIds) {
                if (ps.readUserState(userId).installed) {
                    installedUsers.add(userId);
                }
            }
            return installedUsers.getAll();
        }
        return new int[0];
    }

    @Override
    public List<InstalledAppInfo> getInstalledApps(int flags) {
        List<InstalledAppInfo> infoList = new ArrayList<>(getInstalledAppCount());
        for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
            PackageSetting setting = (PackageSetting) p.mExtras;
            infoList.add(setting.getAppInfo());
        }
        return infoList;
    }

    @Override
    public List<InstalledAppInfo> getInstalledAppsAsUser(int userId, int flags) {
        List<InstalledAppInfo> infoList = new ArrayList<>(getInstalledAppCount());
        for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
            PackageSetting setting = (PackageSetting) p.mExtras;
            boolean visible = setting.isInstalled(userId);
            if ((flags & VirtualCore.GET_HIDDEN_APP) == 0 && setting.isHidden(userId)) {
                visible = false;
            }
            if (visible) {
                infoList.add(setting.getAppInfo());
            }
        }
        return infoList;
    }

    @Override
    public int getInstalledAppCount() {
        return PackageCacheManager.PACKAGE_CACHE.size();
    }

    @Override
    public boolean isAppInstalled(String packageName) {
        return packageName != null && PackageCacheManager.PACKAGE_CACHE.containsKey(packageName);
    }

    @Override
    public boolean isAppInstalledAsUser(int userId, String packageName) {
        if (packageName == null || !VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        if (setting == null) {
            return false;
        }
        return setting.isInstalled(userId);
    }

    private void notifyAppInstalled(PackageSetting setting, int userId) {
        final String pkg = setting.packageName;
        int N = mRemoteCallbackList.beginBroadcast();
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    sendInstalledBroadcast(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalled(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalledAsUser(0, pkg);

                } else {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalledAsUser(userId, pkg);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        mRemoteCallbackList.finishBroadcast();
        VAccountManagerService.get().refreshAuthenticatorCache(null);
    }

    private void notifyAppUninstalled(PackageSetting setting, int userId) {
        final String pkg = setting.packageName;
        int N = mRemoteCallbackList.beginBroadcast();
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    sendUninstalledBroadcast(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalled(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalledAsUser(0, pkg);
                } else {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalledAsUser(userId, pkg);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        mRemoteCallbackList.finishBroadcast();
        VAccountManagerService.get().refreshAuthenticatorCache(null);
    }


    private void sendInstalledBroadcast(String packageName) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED);
        intent.setData(Uri.parse("package:" + packageName));
        VActivityManagerService.get().sendBroadcastAsUser(intent, VUserHandle.ALL);
    }

    private void sendUninstalledBroadcast(String packageName) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.parse("package:" + packageName));
        VActivityManagerService.get().sendBroadcastAsUser(intent, VUserHandle.ALL);
    }

    @Override
    public void registerObserver(IPackageObserver observer) {
        try {
            mRemoteCallbackList.register(observer);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void unregisterObserver(IPackageObserver observer) {
        try {
            mRemoteCallbackList.unregister(observer);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public IAppRequestListener getAppRequestListener() {
        return mAppRequestListener;
    }

    @Override
    public void setAppRequestListener(final IAppRequestListener listener) {
        this.mAppRequestListener = listener;
        if (listener != null) {
            try {
                listener.asBinder().linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        listener.asBinder().unlinkToDeath(this, 0);
                        VAppManagerService.this.mAppRequestListener = null;
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void clearAppRequestListener() {
        this.mAppRequestListener = null;
    }

    @Override
    public InstalledAppInfo getInstalledAppInfo(String packageName, int flags) {
        synchronized (PackageCacheManager.class) {
            if (packageName != null) {
                PackageSetting setting = PackageCacheManager.getSetting(packageName);
                if (setting != null) {
                    return setting.getAppInfo();
                }
            }
            return null;
        }
    }

    public boolean isPackageLaunched(int userId, String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        return ps != null && ps.isLaunched(userId);
    }

    public void setPackageHidden(int userId, String packageName, boolean hidden) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null && VUserManagerService.get().exists(userId)) {
            ps.setHidden(userId, hidden);
            mPersistenceLayer.save();
        }
    }

    public int getAppId(String packageName) {
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        return setting != null ? setting.appId : -1;
    }


    void restoreFactoryState() {
        VLog.w(TAG, "Warning: Restore the factory state...");
        VEnvironment.getDalvikCacheDirectory().delete();
        VEnvironment.getUserSystemDirectory().delete();
        VEnvironment.getDataAppDirectory().delete();
    }

    public void savePersistenceData() {
        mPersistenceLayer.save();
    }
}
