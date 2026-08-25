package com.lody.virtual.server.pm;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;

import com.lody.virtual.client.core.InstallStrategy;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.collection.IntArray;
import com.lody.virtual.helper.compat.NativeLibraryHelperCompat;
import com.lody.virtual.helper.utils.ArrayUtils;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.InstallResult;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.IAppManager;
import com.lody.virtual.server.accounts.VAccountManagerService;
import com.lody.virtual.server.am.BroadcastSystem;
import com.lody.virtual.server.am.UidSystem;
import com.lody.virtual.server.am.VActivityManagerService;
import com.lody.virtual.server.job.VJobSchedulerService;
import com.lody.virtual.server.notification.VNotificationManagerService;
import com.lody.virtual.server.interfaces.IAppRequestListener;
import com.lody.virtual.server.interfaces.IPackageObserver;
import com.lody.virtual.server.pm.parser.PackageParserEx;
import com.lody.virtual.server.pm.parser.VPackage;

import java.io.File;
import java.io.FileOutputStream;
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
    private boolean mTrustedQuarantineWriteFailed;

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

    synchronized void removeUserFromPackageSettingsOrThrow(int userId) throws IOException {
        for (VPackage pkg : PackageCacheManager.PACKAGE_CACHE.values()) {
            PackageSetting setting = (PackageSetting) pkg.mExtras;
            setting.removeUser(userId);
        }
        // Persist even when every in-memory state was already removed by an earlier failed attempt.
        mPersistenceLayer.saveOrThrow();
    }

    @Override
    public void scanApps() {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
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
        if (com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                .isTrustedPackage(ps.packageName) && ps.trustedPackageProvenance == null) {
            markTrustedPackageQuarantine(ps);
            VLog.e(TAG, "Rejecting legacy Google services package without trusted provenance");
            return false;
        }
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
        pkg.trustedPackageProvenance = ps.trustedPackageProvenance;
        PackageParserEx.readSignature(pkg, new File(ps.apkPath), ps.trustedPackageProvenance);
        if (pkg.mSignatures == null || pkg.mRealSignatures == null) {
            VLog.e(TAG, "Rejecting package whose activated signer cannot be verified");
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
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        if (pkg != null) {
            mVisibleOutsidePackages.add(pkg);
        }
    }

    @Override
    public void removeVisibleOutsidePackage(String pkg) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        if (pkg != null) {
            mVisibleOutsidePackages.remove(pkg);
        }
    }

    @Override
    public InstallResult installPackage(String path, int flags) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        return installPackage(path, flags, true);
    }

    public synchronized InstallResult installPackage(String path, int flags, boolean notify) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        return installPackageTransactional(
                path, flags, notify, PackageInstallScope.GLOBAL_USER_ID);
    }

    /**
     * Installs or updates shared package code and exposes it only to the requested virtual user.
     * Existing users keep their installed state when the shared code is updated.
     */
    public synchronized InstallResult installPackageForUser(String path, int flags, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        if (!VUserManagerService.get().exists(userId)) {
            return InstallResult.makeFailure("User " + userId + " does not exist.");
        }

        return installPackageTransactional(path, flags, true, userId, null);
    }

    @Override
    public synchronized InstallResult installTrustedPackageForUser(
            String path,
            int flags,
            int userId,
            com.lody.virtual.remote.TrustedPackageProvenance provenance) {
        enforceTrustedGmsHostCaller();
        if (mTrustedQuarantineWriteFailed || hasTrustedPackageQuarantine()) {
            return InstallResult.makeFailure("Trusted package quarantine is incomplete.");
        }
        if (!VUserManagerService.get().exists(userId)) {
            return InstallResult.makeFailure("User " + userId + " does not exist.");
        }
        if (provenance == null) {
            return InstallResult.makeFailure("Trusted provenance is required.");
        }
        return installPackageTransactional(path, flags, true, userId, provenance);
    }

    private void markTrustedPackageQuarantine(PackageSetting setting) {
        for (int userId : VUserManagerService.get().getUserIds()) {
            // Legacy fake-signature provenance cannot prove which virtual users previously owned
            // authenticator-only residual state. Quarantine every active user fail-closed; the
            // product explicitly does not preserve compatibility with these unsafe installs.
            File marker = trustedQuarantineMarker(setting.packageName, userId);
            try (FileOutputStream output = new FileOutputStream(marker)) {
                output.write(1);
                output.getFD().sync();
                PackageInstallTransaction.syncDirectory(marker.getParentFile());
            } catch (IOException failure) {
                mTrustedQuarantineWriteFailed = true;
            }
        }
    }

    private File trustedQuarantineMarker(String packageName, int userId) {
        return new File(VEnvironment.getTrustedPackageQuarantineDirectory(),
                packageName.replace('.', '_') + "-" + userId);
    }

    private boolean hasTrustedPackageQuarantine() {
        File[] markers = VEnvironment.getTrustedPackageQuarantineDirectory().listFiles();
        return markers == null || markers.length > 0;
    }

    /** Completes legacy trusted-package purge after AccountManager and all ownership services load. */
    public synchronized void completeTrustedPackageQuarantine() {
        File directory = VEnvironment.getTrustedPackageQuarantineDirectory();
        File[] markers = directory.listFiles();
        if (markers == null) {
            mTrustedQuarantineWriteFailed = true;
            return;
        }
        for (File marker : markers) {
            String name = marker.getName();
            int separator = name.lastIndexOf('-');
            if (separator < 0) continue;
            int userId;
            try {
                userId = Integer.parseInt(name.substring(separator + 1));
            } catch (NumberFormatException malformed) {
                continue;
            }
            String packageName = name.substring(0, separator).replace('_', '.');
            if (!com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                    .isTrustedPackage(packageName)) continue;
            try {
                // No compatibility is promised for legacy generic-spoof installs. Clear only the
                // fixed trusted package account types; unrelated clone authenticators must survive.
                boolean gmsCore = com.lody.virtual.server.pm.parser
                        .TrustedSignatureOverridePolicy.TRUSTED_PACKAGE.equals(packageName);
                if (!VAccountManagerService.get()
                        .clearPackageState(packageName, userId, gmsCore)) continue;
                VJobSchedulerService.get().clearPackageState(packageName, userId);
                VNotificationManagerService.getOrCreate(VirtualCore.get().getContext())
                        .clearPackageState(packageName, userId);
                VActivityManagerService.get().clearPendingIntentState(packageName, userId);
                if (!VPackageManagerService.get()
                        .clearRuntimePermissionsInternal(packageName, userId)) continue;
                deletePackageDataOrThrow(packageName, userId);
                VUserManagerService.get().bumpPackagePendingIntentGenerationOrThrow(
                        packageName, userId);
                if (hasPackageDataState(packageName, userId)) continue;
                if (!marker.delete()) continue;
                PackageInstallTransaction.syncDirectory(marker.getParentFile());
            } catch (Throwable incomplete) {
                // Marker remains durable and installTrusted continues to reject.
            }
        }
        mTrustedQuarantineWriteFailed = false;
    }

    /**
     * Suspends the one trusted GmsCore binding while preserving CE/DE/private data and permissions.
     * This is intentionally package-fixed so it cannot become a generic host uninstall primitive.
     */
    @Override
    public synchronized boolean suspendTrustedGmsPackageForUser(int userId) {
        enforceTrustedGmsHostCaller();
        if (userId <= 0 || !VUserManagerService.get().exists(userId)) return false;
        return suspendTrustedPackageForUser(
                com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                        .TRUSTED_COMPANION_PACKAGE,
                userId)
                && suspendTrustedPackageForUser(
                com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy.TRUSTED_PACKAGE,
                userId);
    }

    private boolean suspendTrustedPackageForUser(final String packageName, final int userId) {
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        if (setting == null) return true;
        if (setting.trustedPackageProvenance == null) return false;
        try {
            // Persist the revocation before cancelling in-memory tokens. A crash immediately after
            // this point leaves every old host PendingIntent harmless on the next dispatch.
            VUserManagerService.get().bumpPackagePendingIntentGenerationOrThrow(
                    packageName, userId);
        } catch (IOException epochFailure) {
            return false;
        }

        final VActivityManagerService activityManager = VActivityManagerService.get();
        final boolean wasInstalled = setting.isInstalled(userId);
        boolean suspended = TrustedGmsSuspensionCoordinator.suspend(
                new TrustedGmsSuspensionCoordinator.Operations() {
                    @Override
                    public void killProcesses() {
                        if (com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                                .TRUSTED_PACKAGE.equals(packageName)) {
                            activityManager.stopTrustedGmsCloudMessagingForUser(userId);
                        }
                        activityManager.killAppByPkg(packageName, userId);
                    }

                    @Override
                    public void clearJobs() {
                        VJobSchedulerService.get().clearPackageState(packageName, userId);
                    }

                    @Override
                    public void clearNotifications() throws IOException {
                        VNotificationManagerService.getOrCreate(VirtualCore.get().getContext())
                                .clearPackageState(packageName, userId);
                    }

                    @Override
                    public void clearPendingIntents() {
                        activityManager.clearPendingIntentState(packageName, userId);
                    }

                    @Override
                    public boolean isInstalled() {
                        return setting.isInstalled(userId);
                    }

                    @Override
                    public void commitUnbind() throws IOException {
                        EqualVersionUserBinding.unbind(
                                installedStateAccessor(setting),
                                userId,
                                mPersistenceLayer::saveOrThrow);
                    }

                    @Override
                    public boolean hasBackgroundOwnership() {
                        return hasPackageBackgroundState(packageName, userId);
                    }
                });
        if (!suspended) {
            VLog.e(TAG, "Unable to suspend trusted GmsCore for user %d", userId);
            return false;
        }
        if (wasInstalled) notifyAppUninstalled(setting, userId);
        return true;
    }

    @Override
    public boolean hasTrustedGmsBackgroundStateForUser(int userId) {
        enforceTrustedGmsHostCaller();
        if (userId <= 0 || !VUserManagerService.get().exists(userId)) return true;
        try {
            for (String packageName : new String[]{
                    com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy.TRUSTED_PACKAGE,
                    com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                            .TRUSTED_COMPANION_PACKAGE}) {
                if (hasPackageBackgroundState(packageName, userId)) return true;
            }
            return false;
        } catch (Throwable observationFailure) {
            return true;
        }
    }

    private boolean hasPackageBackgroundState(String packageName, int userId) {
        return VJobSchedulerService.get().hasPackageState(packageName, userId)
                || VNotificationManagerService.getOrCreate(VirtualCore.get().getContext())
                .hasPackageState(packageName, userId)
                || VActivityManagerService.get().hasPendingIntentState(packageName, userId);
    }

    private void enforceTrustedGmsHostCaller() {
        TrustedGmsCallerPolicy.enforceHost(
                VBinder.getCallingUid(), VirtualCore.get().myUid());
    }

    private InstallResult installPackageTransactional(String path, int flags, boolean notify,
                                                       int requestedUserId) {
        return installPackageTransactional(path, flags, notify, requestedUserId, null);
    }

    private InstallResult installPackageTransactional(
            String path,
            int flags,
            boolean notify,
            int requestedUserId,
            com.lody.virtual.remote.TrustedPackageProvenance provenance) {
        VPackage stagedPackage = parseStagedPackage(path, provenance);
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

            InstallResult result = installPackageInternal(path, flags, requestedUserId, provenance);
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

    private VPackage parseStagedPackage(
            String path, com.lody.virtual.remote.TrustedPackageProvenance provenance) {
        if (path == null) {
            return null;
        }
        try {
            return PackageParserEx.parsePackage(new File(path), provenance);
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
        return installPackageInternal(path, flags, requestedUserId, null);
    }

    private InstallResult installPackageInternal(
            String path,
            int flags,
            int requestedUserId,
            com.lody.virtual.remote.TrustedPackageProvenance provenance) {
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
            pkg = PackageParserEx.parsePackage(packageFile, provenance);
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
            if (!PackageSignaturePolicy.isCompatible(
                    PackageSignaturePolicy.signerForUpdate(existOne),
                    PackageSignaturePolicy.signerForUpdate(pkg))) {
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

        if (provenance != null) {
            // Trusted artifacts must always use the bytes verified by AppTwin, never a mutable
            // package exposed by the physical ROM.
            dependSystem = false;
        }

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
            if (NativeLibraryHelperCompat.copyNativeBinaries(
                    ActivatedPackageInputs.nativeLibrarySource(privatePackageFile), libDir) < 0) {
                privatePackageFile.delete();
                return InstallResult.makeFailure("Unable to extract native libraries from base APK.");
            }

            packageFile = privatePackageFile;

            if (pkg.splitNames != null) {
                int length = pkg.splitNames.length;
                splitCodePaths = new String[length];

                for (int i = 0; i < length; i++) {
                    String splitName = pkg.splitNames[i];
                    File privateSplitFile = new File(appDir, privateSplitFileName(splitName));
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

        if (!dependSystem) {
            try {
                // The source path is caller-controlled and can change after the first parse. Build
                // the activated cache only from the private bytes that were actually copied.
                File activatedPath = ActivatedPackageInputs.parseRoot(
                        packageFile, appDir, splitCodePaths != null);
                VPackage activatedPackage = PackageParserEx.parsePackage(activatedPath, provenance);
                if (!TextUtils.equals(pkg.packageName, activatedPackage.packageName)
                        || pkg.mVersionCode != activatedPackage.mVersionCode) {
                    return InstallResult.makeFailure("Activated package identity changed while copying.");
                }
                pkg = activatedPackage;
            } catch (Throwable verificationFailure) {
                return InstallResult.makeFailure("Unable to verify activated package bytes.");
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
        ps.trustedPackageProvenance = pkg.trustedPackageProvenance;
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

    /**
     * Android's installed-split contract uses split_<name>.apk beside base.apk. Some apps derive
     * this path from splitNames instead of trusting ApplicationInfo.splitSourceDirs, so preserving
     * the platform filename is required even when the package is stored privately.
     */
    static String privateSplitFileName(String splitName) {
        return "split_" + splitName + ".apk";
    }


    @Override
    public synchronized boolean installPackageAsUser(int userId, String packageName) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
        if (com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                .isTrustedPackage(packageName)) {
            // Trusted compatibility bindings require pinned bytes + provenance through the
            // dedicated host-only transaction; cached shared code is never sufficient authority.
            return false;
        }
        if (VUserManagerService.get().exists(userId)) {
            PackageSetting ps = PackageCacheManager.getSetting(packageName);
            if (ps != null) {
                if (!ps.isInstalled(userId)) {
                    if (!ensurePlatformSplitFileNames(PackageCacheManager.get(packageName), ps)) {
                        VLog.e(TAG, "Unable to normalize split APK filenames before adding %s to user %d",
                                packageName, userId);
                        return false;
                    }
                    if (!VPackageManagerService.get()
                            .clearRuntimePermissionsInternal(packageName, userId)) {
                        // A newly added binding must not inherit a stale decision from an earlier
                        // binding whose cleanup was interrupted.
                        return false;
                    }
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

    private boolean ensurePlatformSplitFileNames(VPackage pkg, PackageSetting setting) {
        if (setting.dependSystem || pkg == null || pkg.splitNames == null
                || setting.splitCodePaths == null) {
            return true;
        }
        if (pkg.splitNames.length != setting.splitCodePaths.length) {
            return false;
        }
        File appDirectory = new File(setting.apkPath).getParentFile();
        if (appDirectory == null) {
            return false;
        }
        String[] normalizedPaths = setting.splitCodePaths.clone();
        for (int index = 0; index < pkg.splitNames.length; index++) {
            File source = new File(setting.splitCodePaths[index]);
            File normalized = new File(appDirectory, privateSplitFileName(pkg.splitNames[index]));
            if (!normalized.equals(source) && !normalized.isFile()) {
                try {
                    FileUtils.copyFile(source, normalized);
                } catch (IOException migrationFailure) {
                    normalized.delete();
                    return false;
                }
            }
            normalizedPaths[index] = normalized.getPath();
        }
        setting.splitCodePaths = normalizedPaths;
        pkg.splitCodePaths = normalizedPaths.clone();
        // PackageManager queries are generated from the cached VPackage ApplicationInfo, while
        // process binding also receives InstalledAppInfo. Keep both views on the normalized paths
        // so dynamic module loaders do not see a stale split list after an existing install is
        // migrated.
        PackageParserEx.initApplicationInfoBase(setting, pkg);
        return true;
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
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            return uninstallPackageFully(ps);
        }
        return false;
    }

    @Override
    public boolean clearPackageAsUser(int userId, String packageName) throws RemoteException {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
        if (com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                .isTrustedPackage(packageName)) {
            com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        }
        if (!VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            VActivityManagerService activityManager = VActivityManagerService.get();
            VActivityManagerService.LinePushPackageStateMutation lineMutation =
                    activityManager.beginLinePushPackageStateMutation(packageName, userId);
            try {
                int[] userIds = getPackageInstalledUsersInternal(packageName);
                if (!ArrayUtils.contains(userIds, userId)) {
                    return false;
                }
                if (!VPackageManagerService.get()
                        .clearRuntimePermissionsInternal(packageName, userId)) {
                    VLog.e(TAG, "UNINSTALL_PERMISSION_RETRYABLE");
                    return false;
                }
                if (!GuestKeystoreState.clearPackageState(packageName, userId)) {
                    VLog.e(TAG, "UNINSTALL_KEYSTORE_RETRYABLE");
                    return false;
                }
                if (userIds.length == 1) {
                    // Self clear is user-scoped. Do not route through the host-only global helper,
                    // which would kill/erase other virtual users if residual state exists.
                    activityManager.killAppByPkg(packageName, userId);
                    FileUtils.deleteDir(
                            VEnvironment.getDataUserPackageDirectory(userId, packageName));
                    FileUtils.deleteDir(
                            VEnvironment.getDeDataUserPackageDirectory(userId, packageName));
                    FileUtils.deleteDir(
                            VEnvironment.getVirtualPrivateStorageDir(userId, packageName));
                } else {
                    // Just hidden it
                    activityManager.killAppByPkg(packageName, userId);
                    ps.setInstalled(userId, false);
                    mPersistenceLayer.save();
                    FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, packageName));
                    FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(userId, packageName));
                    FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(userId, packageName));
                }
                return true;
            } finally {
                activityManager.endLinePushPackageStateMutation(lineMutation);
            }
        }
        return false;
    }

    /**
     * Clears all mutable state owned by one package/user pair without changing its installed
     * bit. In particular, this deliberately excludes the user-wide shared virtual SD card: apps
     * in one virtual user share it, so deleting it here could erase another clone's media.
     */
    @Override
    public synchronized boolean clearPackageRuntimeStateAsUser(
            int userId, String packageName) throws RemoteException {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
        if (!VUserManagerService.get().exists(userId)) return false;
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        if (setting == null || !ArrayUtils.contains(getPackageInstalledUsersInternal(packageName), userId)) {
            return false;
        }
        return clearPackageRuntimeState(packageName, userId);
    }

    @Override
    public boolean clearPackage(String packageName) throws RemoteException {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        return clearPackageInternal(packageName);
    }

    private boolean clearPackageInternal(String packageName) {
        VActivityManagerService activityManager = VActivityManagerService.get();
        VActivityManagerService.LinePushPackageStateMutation lineMutation =
                activityManager.beginLinePushPackageStateMutation(
                        packageName, VUserHandle.USER_ALL);
        try {
            BroadcastSystem.get().stopApp(packageName);
            activityManager.killAppByPkg(packageName, VUserHandle.USER_ALL);

            for (int id : VUserManagerService.get().getUserIds()) {
                if (!VPackageManagerService.get()
                        .clearRuntimePermissionsInternal(packageName, id)) {
                    return false;
                }
                if (!GuestKeystoreState.clearPackageState(packageName, id)) return false;
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(id, packageName));
                FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(id, packageName));
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(id, packageName));
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            activityManager.endLinePushPackageStateMutation(lineMutation);
        }
    }

    @Override
    public synchronized boolean uninstallPackageAsUser(String packageName, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
        if (com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                .isTrustedPackage(packageName)) {
            com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        }
        if (!VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            VActivityManagerService activityManager = VActivityManagerService.get();
            VActivityManagerService.LinePushPackageStateMutation lineMutation =
                    activityManager.beginLinePushPackageStateMutation(packageName, userId);
            try {
            if (com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                    .isTrustedPackage(packageName)) {
                try {
                    VUserManagerService.get().bumpPackagePendingIntentGenerationOrThrow(
                            packageName, userId);
                } catch (IOException epochFailure) {
                    return false;
                }
            }
            if (!VPackageManagerService.get()
                    .clearRuntimePermissionsInternal(packageName, userId)) {
                // Never leave a durable grant that could be inherited if this binding is re-added.
                return false;
            }
            if (!GuestKeystoreState.clearPackageState(packageName, userId)) {
                VLog.e(TAG, "UNINSTALL_KEYSTORE_RETRYABLE");
                return false;
            }
            // User-scoped uninstall only removes the binding and private data. Shared code remains
            // available as a revision cache even when this was the last installed user.
            activityManager.killAppByPkg(packageName, userId);
            try {
                VJobSchedulerService.get().clearPackageState(packageName, userId);
                VNotificationManagerService.getOrCreate(VirtualCore.get().getContext())
                        .clearPackageState(packageName, userId);
                activityManager.clearPendingIntentState(packageName, userId);
                VAccountManagerService accountManager = VAccountManagerService.get();
                boolean trustedGms = com.lody.virtual.server.pm.parser
                        .TrustedSignatureOverridePolicy.TRUSTED_PACKAGE.equals(packageName);
                if (accountManager == null
                        || !accountManager.clearPackageState(packageName, userId, trustedGms)) {
                    VLog.e(TAG, "UNINSTALL_ACCOUNT_RETRYABLE");
                    return false;
                }
            } catch (Throwable cleanupFailure) {
                VLog.e(TAG, "UNINSTALL_OWNERSHIP_RETRYABLE");
                return false;
            }
            boolean wasInstalled = ps.isInstalled(userId);
            try {
                if (wasInstalled) {
                    EqualVersionUserBinding.unbind(
                            installedStateAccessor(ps),
                            userId,
                            mPersistenceLayer::saveOrThrow);
                }
            } catch (IOException commitFailure) {
                VLog.e(TAG, "Unable to persist package removal for user %d: %s",
                        userId, "UNINSTALL_UNBIND_RETRYABLE");
                return false;
            }
            if (wasInstalled) notifyAppUninstalled(ps, userId);
            try {
                deletePackageDataOrThrow(packageName, userId);
            } catch (IOException dataFailure) {
                VLog.e(TAG, "UNINSTALL_DATA_RETRYABLE");
                return false;
            }
            VAccountManagerService accountManager = VAccountManagerService.get();
            boolean trustedGms = com.lody.virtual.server.pm.parser
                    .TrustedSignatureOverridePolicy.TRUSTED_PACKAGE.equals(packageName);
            boolean terminal = !ps.isInstalled(userId)
                    && !VJobSchedulerService.get().hasPackageState(packageName, userId)
                    && !VNotificationManagerService.getOrCreate(VirtualCore.get().getContext())
                    .hasPackageState(packageName, userId)
                    && !activityManager.hasPendingIntentState(packageName, userId)
                    && accountManager != null
                    && !accountManager.hasPackageState(packageName, userId, trustedGms)
                    && !GuestKeystoreState.hasPackageState(packageName, userId)
                    && !hasPackageDataState(packageName, userId);
            if (!terminal) VLog.e(TAG, "UNINSTALL_TERMINAL_RETRYABLE");
            return terminal;
            } finally {
                activityManager.endLinePushPackageStateMutation(lineMutation);
            }
        }
        return com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                .isTrustedPackage(packageName)
                && clearTrustedResidualState(packageName, userId);
    }

    @Override
    public synchronized boolean clearTrustedPackageStateForUser(
            String packageName, int userId) {
        enforceTrustedGmsHostCaller();
        if (!com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                .isTrustedPackage(packageName)
                || userId <= 0 || !VUserManagerService.get().exists(userId)) return false;
        return uninstallPackageAsUser(packageName, userId);
    }

    private boolean clearTrustedResidualState(String packageName, int userId) {
        return clearPackageRuntimeState(packageName, userId);
    }

    /** Clears package-owned runtime state while preserving PackageSetting installed metadata. */
    private boolean clearPackageRuntimeState(String packageName, int userId) {
        VActivityManagerService activityManager = VActivityManagerService.get();
        VActivityManagerService.LinePushPackageStateMutation lineMutation =
                activityManager.beginLinePushPackageStateMutation(packageName, userId);
        try {
            VUserManagerService.get().bumpPackagePendingIntentGenerationOrThrow(
                    packageName, userId);
            if (!VPackageManagerService.get()
                    .clearRuntimePermissionsInternal(packageName, userId)) return false;
            if (!GuestKeystoreState.clearPackageState(packageName, userId)) return false;
            activityManager.killAppByPkg(packageName, userId);
            VJobSchedulerService.get().clearPackageState(packageName, userId);
            VNotificationManagerService.getOrCreate(VirtualCore.get().getContext())
                    .clearPackageState(packageName, userId);
            activityManager.clearPendingIntentState(packageName, userId);
            VAccountManagerService accountManager = VAccountManagerService.get();
            boolean gmsCore = com.lody.virtual.server.pm.parser
                    .TrustedSignatureOverridePolicy.TRUSTED_PACKAGE.equals(packageName);
            if (accountManager == null
                    || !accountManager.clearPackageState(packageName, userId, gmsCore)) return false;
            deletePackageDataOrThrow(packageName, userId);
            return !VJobSchedulerService.get().hasPackageState(packageName, userId)
                    && !VNotificationManagerService.getOrCreate(VirtualCore.get().getContext())
                    .hasPackageState(packageName, userId)
                    && !activityManager.hasPendingIntentState(packageName, userId)
                    && !accountManager.hasPackageState(packageName, userId, gmsCore)
                    && !GuestKeystoreState.hasPackageState(packageName, userId)
                    && !hasPackageDataState(packageName, userId);
        } catch (Throwable incomplete) {
            return false;
        } finally {
            activityManager.endLinePushPackageStateMutation(lineMutation);
        }
    }

    private boolean hasPackageDataState(String packageName, int userId) {
        if (new File(VEnvironment.getUserSystemDirectory(userId), packageName).exists()) return true;
        if (new File(VEnvironment.getDeUserSystemDirectory(userId), packageName).exists()) return true;
        File externalFiles = VirtualCore.get().getContext().getExternalFilesDir(null);
        // External storage is part of trusted GMS private state.  Unmounted is unknown, not absent.
        return externalFiles == null || new File(
                com.lody.virtual.os.VirtualExternalStorageLayout.privateStorageForUser(
                        externalFiles, userId), packageName).exists();
    }

    private void deletePackageDataOrThrow(String packageName, int userId) throws IOException {
        File externalFiles = VirtualCore.get().getContext().getExternalFilesDir(null);
        if (externalFiles == null) throw new IOException("External private storage unavailable");
        VUserManagerService.removeDirectoryRecursiveOrThrow(
                new File(VEnvironment.getUserSystemDirectory(userId), packageName));
        VUserManagerService.removeDirectoryRecursiveOrThrow(
                new File(VEnvironment.getDeUserSystemDirectory(userId), packageName));
        VUserManagerService.removeDirectoryRecursiveOrThrow(new File(
                com.lody.virtual.os.VirtualExternalStorageLayout.privateStorageForUser(
                        externalFiles, userId), packageName));
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

    private boolean uninstallPackageFully(PackageSetting ps) {
        String packageName = ps.packageName;
        VActivityManagerService activityManager = VActivityManagerService.get();
        VActivityManagerService.LinePushPackageStateMutation lineMutation =
                activityManager.beginLinePushPackageStateMutation(
                        packageName, VUserHandle.USER_ALL);
        try {
            if (com.lody.virtual.server.pm.parser.TrustedSignatureOverridePolicy
                    .isTrustedPackage(packageName)) {
                for (int userId : VUserManagerService.get().getUserIds()) {
                    if (!uninstallPackageAsUser(packageName, userId)) return false;
                }
            }
            BroadcastSystem.get().stopApp(packageName);
            activityManager.killAppByPkg(packageName, VUserHandle.USER_ALL);
            VEnvironment.getPackageResourcePath(packageName).delete();
            FileUtils.deleteDir(VEnvironment.getDataAppPackageDirectory(packageName));
            VEnvironment.getOdexFile(packageName).delete();
            for (int id : VUserManagerService.get().getUserIds()) {
                VPackageManagerService.get().clearRuntimePermissionsInternal(packageName, id);
                if (!GuestKeystoreState.clearPackageState(packageName, id)) return false;
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(id, packageName));
                FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(id, packageName));
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(id, packageName));
            }
            PackageCacheManager.remove(packageName);
            notifyAppUninstalled(ps, -1);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            activityManager.endLinePushPackageStateMutation(lineMutation);
        }
    }

    @Override
    public int[] getPackageInstalledUsers(String packageName) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        return getPackageInstalledUsersInternal(packageName);
    }

    private int[] getPackageInstalledUsersInternal(String packageName) {
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
        if (!com.lody.virtual.server.VirtualUserAccessPolicy.isHostCaller()) {
            int callerUser = VUserHandle.getUserId(VBinder.getCallingUid());
            return getInstalledAppsAsUser(callerUser, flags);
        }
        List<InstalledAppInfo> infoList = new ArrayList<>(getInstalledAppCount());
        for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
            PackageSetting setting = (PackageSetting) p.mExtras;
            infoList.add(setting.getAppInfo());
        }
        return infoList;
    }

    @Override
    public List<InstalledAppInfo> getInstalledAppsAsUser(int userId, int flags) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
        List<InstalledAppInfo> infoList = new ArrayList<>(PackageCacheManager.PACKAGE_CACHE.size());
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
        if (!com.lody.virtual.server.VirtualUserAccessPolicy.isHostCaller()) {
            return getInstalledAppsAsUser(
                    VUserHandle.getUserId(VBinder.getCallingUid()), 0).size();
        }
        return PackageCacheManager.PACKAGE_CACHE.size();
    }

    @Override
    public boolean isAppInstalled(String packageName) {
        if (packageName == null) return false;
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        if (setting == null) return false;
        if (com.lody.virtual.server.VirtualUserAccessPolicy.isHostCaller()) return true;
        int callingVuid = VBinder.getCallingUid();
        return callingVuid >= 0
                && setting.isInstalled(VUserHandle.getUserId(callingVuid));
    }

    @Override
    public boolean isAppInstalledAsUser(int userId, String packageName) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
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
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        try {
            mRemoteCallbackList.register(observer);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void unregisterObserver(IPackageObserver observer) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
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
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
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
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        this.mAppRequestListener = null;
    }

    @Override
    public InstalledAppInfo getInstalledAppInfo(String packageName, int flags) {
        synchronized (PackageCacheManager.class) {
            if (packageName != null) {
                PackageSetting setting = PackageCacheManager.getSetting(packageName);
                int callingVuid = VBinder.getCallingUid();
                if (setting != null && (com.lody.virtual.server.VirtualUserAccessPolicy
                        .isHostCaller() || (callingVuid >= 0 && setting.isInstalled(
                        VUserHandle.getUserId(callingVuid))))) {
                    return setting.getAppInfo();
                }
            }
            return null;
        }
    }

    public boolean isPackageLaunched(int userId, String packageName) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        return ps != null && ps.isLaunched(userId);
    }

    public void setPackageHidden(int userId, String packageName, boolean hidden) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
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
