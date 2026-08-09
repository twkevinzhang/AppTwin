package com.lody.virtual.server.pm.parser;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.text.TextUtils;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.fixer.ComponentFixer;
import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.compat.PackageParserCompat;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.AtomicFile;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.PackageUserState;
import com.lody.virtual.remote.TrustedPackageProvenance;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mirror.android.content.pm.ApplicationInfoL;
import mirror.android.content.pm.ApplicationInfoN;

/**
 * @author Lody
 */

public class PackageParserEx {

    private static final String TAG = PackageParserEx.class.getSimpleName();

    private static final ArrayMap<String, String[]> sSharedLibCache = new ArrayMap<>();

    private static final String SIGNATURE_FILE_MAGIC = "ATSG";
    private static final int SIGNATURE_FILE_VERSION = 1;

    public static VPackage parsePackage(File packageFile) throws Throwable {
        return parsePackage(packageFile, null);
    }

    /** Parses a package, optionally applying the one trusted compatibility-signature policy. */
    public static VPackage parsePackage(File packageFile, TrustedPackageProvenance provenance)
            throws Throwable {
        PackageParser parser = PackageParserCompat.createParser(packageFile);
        PackageParser.Package p = PackageParserCompat.parsePackage(parser, packageFile, 0);
        if (TrustedSignatureOverridePolicy.isTrustedPackage(p.packageName)
                && provenance == null) {
            throw new SecurityException(
                    "Google services package requires authenticated trusted provenance");
        }
        // AndroidManifest metadata never authorizes an override. Certificate parsing is mandatory
        // for both generic and trusted installs; any failure aborts the install.
        PackageParserCompat.collectCertificates(parser, p, PackageParser.PARSE_IS_SYSTEM);
        VPackage realPackage = buildPackageCache(p);
        Signature[] realSignatures = cloneSignatures(realPackage.mSignatures);
        if (provenance != null) {
            TrustedSignatureOverridePolicy policy = new TrustedSignatureOverridePolicy();
            policy.authorize(
                    artifactFacts(packageFile, p, realSignatures), provenance);
            buildSignature(p, new Signature[]{new Signature(provenance.effectiveSignature)});
        }
        VPackage result = provenance == null ? realPackage : buildPackageCache(p);
        UnsupportedCompanionComponents.removeFrom(result);
        result.mRealSignatures = realSignatures;
        result.trustedPackageProvenance = provenance;
        return result;
    }

    private static TrustedSignatureOverridePolicy.ArtifactFacts artifactFacts(
            File packageFile, PackageParser.Package parsed, Signature[] realSignatures)
            throws IOException {
        File baseFile = packageFile.isFile() ? packageFile : new File(parsed.baseCodePath);
        ArrayList<String> signerDigests = new ArrayList<>();
        if (realSignatures != null) {
            for (Signature signature : realSignatures) {
                signerDigests.add(TrustedSignatureOverridePolicy.sha256(signature.toByteArray()));
            }
        }
        Map<String, String> splitDigests = new LinkedHashMap<>();
        if (parsed.splitNames != null || parsed.splitCodePaths != null) {
            if (parsed.splitNames == null || parsed.splitCodePaths == null
                    || parsed.splitNames.length != parsed.splitCodePaths.length) {
                throw new SecurityException("inconsistent split package metadata");
            }
            for (int i = 0; i < parsed.splitNames.length; i++) {
                splitDigests.put(parsed.splitNames[i],
                        TrustedSignatureOverridePolicy.sha256(new File(parsed.splitCodePaths[i])));
            }
        }
        return new TrustedSignatureOverridePolicy.ArtifactFacts(
                parsed.packageName,
                parsed.mVersionCode,
                signerDigests,
                TrustedSignatureOverridePolicy.sha256(baseFile),
                splitDigests);
    }

    private static Signature[] cloneSignatures(Signature[] signatures) {
        return signatures == null ? null : Arrays.copyOf(signatures, signatures.length);
    }

    private static void buildSignature(PackageParser.Package p, Signature[] signatures) {
        if (Build.VERSION.SDK_INT < 28) {
            p.mSignatures = signatures;
        } else {
            Object signingDetails = mirror.android.content.pm.PackageParser.Package.mSigningDetails.get(p);
            mirror.android.content.pm.PackageParser.SigningDetails.pastSigningCertificates.set(signingDetails, signatures);
            mirror.android.content.pm.PackageParser.SigningDetails.signatures.set(signingDetails, signatures);
        }
    }

    /**
     * PackageParser.Package keeps the legacy nested SigningDetails through API
     * 37. Android 13 changed only SigningInfo's constructor to require the
     * top-level SigningDetails type, which must be rebuilt from those fields.
     */
    static boolean requiresTopLevelSigningDetails(int apiLevel) {
        return apiLevel >= Build.VERSION_CODES.TIRAMISU;
    }

    private static boolean hasPastSigningCertificates(Object signingDetails) {
        return mirror.android.content.pm.PackageParser.SigningDetails.hasPastSigningCertificates.call(signingDetails);
    }

    private static boolean hasSignatures(Object signingDetails) {
        return mirror.android.content.pm.PackageParser.SigningDetails.hasSignatures.call(signingDetails);
    }

    private static Signature[] getPastSigningCertificates(Object signingDetails) {
        return mirror.android.content.pm.PackageParser.SigningDetails.pastSigningCertificates.get(signingDetails);
    }

    private static Signature[] getSignatures(Object signingDetails) {
        return mirror.android.content.pm.PackageParser.SigningDetails.signatures.get(signingDetails);
    }

    private static android.content.pm.SigningInfo createSigningInfo(Object signingDetails) {
        if (!requiresTopLevelSigningDetails(Build.VERSION.SDK_INT)) {
            return mirror.android.content.pm.PackageParser.SigningInfo.ctor.newInstance(signingDetails);
        }
        Object topLevelSigningDetails = mirror.android.content.pm.PackageParser.SigningDetailsS.ctor.newInstance(
                getSignatures(signingDetails),
                mirror.android.content.pm.PackageParser.SigningDetails.signatureSchemeVersion.get(signingDetails),
                mirror.android.content.pm.PackageParser.SigningDetails.publicKeys.get(signingDetails),
                getPastSigningCertificates(signingDetails));
        return mirror.android.content.pm.PackageParser.SigningInfoS.ctor.newInstance(topLevelSigningDetails);
    }

    public static VPackage readPackageCache(String packageName) {
        Parcel p = Parcel.obtain();
        try {
            File cacheFile = VEnvironment.getPackageCacheFile(packageName);
            FileInputStream is = new FileInputStream(cacheFile);
            byte[] bytes = FileUtils.toByteArray(is);
            is.close();
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            if (p.readInt() != 4) {
                throw new IllegalStateException("Invalid version.");
            }
            VPackage pkg = new VPackage(p);
            // Cache schema predates the product-level Billing/Integrity deny. Reapply on every
            // load so upgrades cannot retain now-forbidden FakeStore components.
            UnsupportedCompanionComponents.removeFrom(pkg);
            addOwner(pkg);
            return pkg;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            p.recycle();
        }
        return null;
    }

    public static void readSignature(VPackage pkg) {
        File installedApk = pkg.applicationInfo == null || pkg.applicationInfo.sourceDir == null
                ? null : new File(pkg.applicationInfo.sourceDir);
        readSignature(pkg, installedApk, pkg.trustedPackageProvenance);
    }

    /** Never trusts persisted signature.ini; rebuilds it from the activated private APK bytes. */
    public static void readSignature(
            VPackage pkg, File installedPackage, TrustedPackageProvenance provenance) {
        File signatureFile = VEnvironment.getSignatureFile(pkg.packageName);
        try {
            if (installedPackage == null || !installedPackage.exists()) {
                throw new IOException("activated package is missing");
            }
            VPackage rebuilt = parsePackage(installedPackage, provenance);
            if (!TextUtils.equals(pkg.packageName, rebuilt.packageName)
                    || pkg.mVersionCode != rebuilt.mVersionCode) {
                throw new SecurityException("activated package identity changed");
            }
            pkg.mSignatures = cloneSignatures(rebuilt.mSignatures);
            pkg.mRealSignatures = cloneSignatures(rebuilt.mRealSignatures);
            pkg.signingInfo = rebuilt.signingInfo;
            writeSignatureCacheAtomic(pkg, signatureFile);
        } catch (Throwable failure) {
            pkg.mSignatures = null;
            pkg.mRealSignatures = null;
            pkg.signingInfo = null;
            new AtomicFile(signatureFile).delete();
        }
    }

    public static void savePackageCache(VPackage pkg) {
        final String packageName = pkg.packageName;
        Parcel p = Parcel.obtain();
        try {
            p.writeInt(4);
            pkg.writeToParcel(p, 0);
            FileOutputStream fos = new FileOutputStream(VEnvironment.getPackageCacheFile(packageName));
            fos.write(p.marshall());
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            p.recycle();
        }
        if (pkg.mSignatures != null) {
            try {
                writeSignatureCacheAtomic(pkg, VEnvironment.getSignatureFile(packageName));
            } catch (IOException failure) {
                new AtomicFile(VEnvironment.getSignatureFile(packageName)).delete();
            }
        }
    }

    private static void writeSignatureCacheAtomic(VPackage pkg, File signatureFile)
            throws IOException {
        Parcel parcel = Parcel.obtain();
        AtomicFile atomicFile = new AtomicFile(signatureFile);
        FileOutputStream output = null;
        try {
            parcel.writeString(SIGNATURE_FILE_MAGIC);
            parcel.writeInt(SIGNATURE_FILE_VERSION);
            parcel.writeTypedArray(pkg.mSignatures, 0);
            parcel.writeTypedArray(pkg.mRealSignatures, 0);
            if (BuildCompat.isPie()) parcel.writeParcelable(pkg.signingInfo, 0);
            output = atomicFile.startWrite();
            output.write(parcel.marshall());
            atomicFile.finishWrite(output);
            output = null;
        } catch (IOException failure) {
            atomicFile.failWrite(output);
            throw failure;
        } finally {
            parcel.recycle();
        }
    }

    private static VPackage buildPackageCache(PackageParser.Package p) {
        VPackage cache = new VPackage();
        cache.activities = new ArrayList<>(p.activities.size());
        cache.services = new ArrayList<>(p.services.size());
        cache.receivers = new ArrayList<>(p.receivers.size());
        cache.providers = new ArrayList<>(p.providers.size());
        cache.instrumentation = new ArrayList<>(p.instrumentation.size());
        cache.permissions = new ArrayList<>(p.permissions.size());
        cache.permissionGroups = new ArrayList<>(p.permissionGroups.size());

        for (PackageParser.Activity activity : p.activities) {
            cache.activities.add(new VPackage.ActivityComponent(activity));
        }
        for (PackageParser.Service service : p.services) {
            cache.services.add(new VPackage.ServiceComponent(service));
        }
        for (PackageParser.Activity receiver : p.receivers) {
            cache.receivers.add(new VPackage.ActivityComponent(receiver));
        }
        for (PackageParser.Provider provider : p.providers) {
            cache.providers.add(new VPackage.ProviderComponent(provider));
        }
        for (PackageParser.Instrumentation instrumentation : p.instrumentation) {
            cache.instrumentation.add(new VPackage.InstrumentationComponent(instrumentation));
        }
        cache.requestedPermissions = new ArrayList<>(p.requestedPermissions.size());
        cache.requestedPermissions.addAll(p.requestedPermissions);
        if (mirror.android.content.pm.PackageParser.Package.protectedBroadcasts != null) {
            List<String> protectedBroadcasts = mirror.android.content.pm.PackageParser.Package.protectedBroadcasts.get(p);
            if (protectedBroadcasts != null) {
                cache.protectedBroadcasts = new ArrayList<>(protectedBroadcasts);
                cache.protectedBroadcasts.addAll(protectedBroadcasts);
            }
        }
        cache.applicationInfo = p.applicationInfo;
        if (Build.VERSION.SDK_INT < 28) {
            cache.mSignatures = p.mSignatures;
        } else {
            Object signingDetails = mirror.android.content.pm.PackageParser.Package.mSigningDetails.get(p);
            boolean hasPastSigningCertificates = hasPastSigningCertificates(signingDetails);
            if (hasPastSigningCertificates) {
                cache.mSignatures = new Signature[1];
                cache.mSignatures[0] = getPastSigningCertificates(signingDetails)[0];
            } else {
                boolean hasSignatures = hasSignatures(signingDetails);
                if (hasSignatures) {
                    Signature[] signatures = getSignatures(signingDetails);
                    int numberOfSigs = signatures.length;
                    cache.mSignatures = new Signature[numberOfSigs];
                    System.arraycopy(signatures, 0, cache.mSignatures, 0, numberOfSigs);
                }
            }
            cache.signingInfo = createSigningInfo(signingDetails);
        }
        cache.mAppMetaData = p.mAppMetaData;
        cache.packageName = p.packageName;
        cache.mPreferredOrder = p.mPreferredOrder;
        cache.mVersionName = p.mVersionName;
        cache.mSharedUserId = p.mSharedUserId;
        cache.mSharedUserLabel = p.mSharedUserLabel;
        cache.usesLibraries = p.usesLibraries;
        cache.mVersionCode = p.mVersionCode;
        cache.mAppMetaData = p.mAppMetaData;
        cache.configPreferences = p.configPreferences;
        cache.reqFeatures = p.reqFeatures;

        // for split apks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cache.splitNames = p.splitNames;
            cache.codePath = p.codePath;
            cache.baseCodePath = p.baseCodePath;
            cache.splitCodePaths = p.splitCodePaths;
        }

        cache.usesOptionalLibraries = p.usesOptionalLibraries;

        addOwner(cache);
        return cache;
    }

    public static void initApplicationInfoBase(PackageSetting ps, VPackage p) {
        ApplicationInfo ai = p.applicationInfo;
        ai.flags |= ApplicationInfo.FLAG_HAS_CODE;
        if (TextUtils.isEmpty(ai.processName)) {
            ai.processName = ai.packageName;
        }
        ai.enabled = true;
        ai.nativeLibraryDir = ps.libPath;
        ai.uid = ps.appId;
        ai.name = ComponentFixer.fixComponentClassName(ps.packageName, ai.name);
        ai.publicSourceDir = ps.apkPath;
        ai.sourceDir = ps.apkPath;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ai.splitSourceDirs = ps.splitCodePaths;
            ai.splitPublicSourceDirs = ps.splitCodePaths;

            ApplicationInfoL.scanSourceDir.set(ai, ai.dataDir);
            ApplicationInfoL.scanPublicSourceDir.set(ai, ai.dataDir);
            String hostPrimaryCpuAbi = ApplicationInfoL.primaryCpuAbi.get(VirtualCore.get().getContext().getApplicationInfo());
            ApplicationInfoL.primaryCpuAbi.set(ai, hostPrimaryCpuAbi);
        }

        if (ps.dependSystem) {
            String[] sharedLibraryFiles = sSharedLibCache.get(ps.packageName);
            if (sharedLibraryFiles == null) {
                PackageManager hostPM = VirtualCore.get().getUnHookPackageManager();
                try {
                    ApplicationInfo hostInfo = hostPM.getApplicationInfo(ps.packageName, PackageManager.GET_SHARED_LIBRARY_FILES);
                    sharedLibraryFiles = hostInfo.sharedLibraryFiles;
                    if (sharedLibraryFiles == null) sharedLibraryFiles = new String[0];
                    sSharedLibCache.put(ps.packageName, sharedLibraryFiles);
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
            }
            ai.sharedLibraryFiles = sharedLibraryFiles;
        }
    }

    private static void initApplicationAsUser(ApplicationInfo ai, int userId) {
        String credentialDataDir = VEnvironment.getDataUserPackageDirectory(userId, ai.packageName).getPath();
        String deviceDataDir = VEnvironment.getDeDataUserPackageDirectory(userId, ai.packageName).getPath();
        initApplicationDataDirs(ai, credentialDataDir, deviceDataDir);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ApplicationInfoL.scanSourceDir.set(ai, ai.dataDir);
            ApplicationInfoL.scanPublicSourceDir.set(ai, ai.dataDir);
        }
    }

    static void initApplicationDataDirs(ApplicationInfo ai, String credentialDataDir,
                                        String deviceDataDir) {
        ai.dataDir = credentialDataDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if(Build.VERSION.SDK_INT < 26) {
                ApplicationInfoN.deviceEncryptedDataDir.set(ai, deviceDataDir);
                ApplicationInfoN.credentialEncryptedDataDir.set(ai, credentialDataDir);
            }
            ApplicationInfoN.deviceProtectedDataDir.set(ai, deviceDataDir);
            ApplicationInfoN.credentialProtectedDataDir.set(ai, credentialDataDir);
        }
    }

    private static void addOwner(VPackage p) {
        for (VPackage.ActivityComponent activity : p.activities) {
            activity.owner = p;
            for (VPackage.ActivityIntentInfo info : activity.intents) {
                info.activity = activity;
            }
        }
        for (VPackage.ServiceComponent service : p.services) {
            service.owner = p;
            for (VPackage.ServiceIntentInfo info : service.intents) {
                info.service = service;
            }
        }
        for (VPackage.ActivityComponent receiver : p.receivers) {
            receiver.owner = p;
            for (VPackage.ActivityIntentInfo info : receiver.intents) {
                info.activity = receiver;
            }
        }
        for (VPackage.ProviderComponent provider : p.providers) {
            provider.owner = p;
            for (VPackage.ProviderIntentInfo info : provider.intents) {
                info.provider = provider;
            }
        }
        for (VPackage.InstrumentationComponent instrumentation : p.instrumentation) {
            instrumentation.owner = p;
        }
        for (VPackage.PermissionComponent permission : p.permissions) {
            permission.owner = p;
        }
        for (VPackage.PermissionGroupComponent group : p.permissionGroups) {
            group.owner = p;
        }
    }

    public static PackageInfo generatePackageInfo(VPackage p, int flags, long firstInstallTime, long lastUpdateTime, PackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        if (p.mSignatures == null) {
            readSignature(p);
        }
        PackageInfo pi = new PackageInfo();
        pi.packageName = p.packageName;
        pi.versionCode = p.mVersionCode;
        pi.sharedUserLabel = p.mSharedUserLabel;
        pi.versionName = p.mVersionName;
        pi.sharedUserId = p.mSharedUserId;
        pi.sharedUserLabel = p.mSharedUserLabel;
        pi.applicationInfo = generateApplicationInfo(p, flags, state, userId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pi.splitNames = p.splitNames != null ? p.splitNames.clone() : new String[0];
            pi.splitRevisionCodes = new int[pi.splitNames.length];
        }
        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if (p.requestedPermissions != null && !p.requestedPermissions.isEmpty()) {
            String[] requestedPermissions = new String[p.requestedPermissions.size()];
            p.requestedPermissions.toArray(requestedPermissions);
            pi.requestedPermissions = requestedPermissions;
        }
        if ((flags & PackageManager.GET_GIDS) != 0) {
            pi.gids = PackageParserCompat.GIDS;
        }
        if ((flags & PackageManager.GET_CONFIGURATIONS) != 0) {
            int N = p.configPreferences != null ? p.configPreferences.size() : 0;
            if (N > 0) {
                pi.configPreferences = new ConfigurationInfo[N];
                p.configPreferences.toArray(pi.configPreferences);
            }
            N = p.reqFeatures != null ? p.reqFeatures.size() : 0;
            if (N > 0) {
                pi.reqFeatures = new FeatureInfo[N];
                p.reqFeatures.toArray(pi.reqFeatures);
            }
        }
        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            final int N = p.activities.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final VPackage.ActivityComponent a = p.activities.get(i);
                    res[num++] = generateActivityInfo(a, flags, state, userId);
                }
                pi.activities = res;
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            final int N = p.receivers.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final VPackage.ActivityComponent a = p.receivers.get(i);
                    res[num++] = generateActivityInfo(a, flags, state, userId);
                }
                pi.receivers = res;
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            final int N = p.services.size();
            if (N > 0) {
                int num = 0;
                final ServiceInfo[] res = new ServiceInfo[N];
                for (int i = 0; i < N; i++) {
                    final VPackage.ServiceComponent s = p.services.get(i);
                    res[num++] = generateServiceInfo(s, flags, state, userId);
                }
                pi.services = res;
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            final int N = p.providers.size();
            if (N > 0) {
                int num = 0;
                final ProviderInfo[] res = new ProviderInfo[N];
                for (int i = 0; i < N; i++) {
                    final VPackage.ProviderComponent pr = p.providers.get(i);
                    res[num++] = generateProviderInfo(pr, flags, state, userId);
                }
                pi.providers = res;
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            int N = p.instrumentation.size();
            if (N > 0) {
                pi.instrumentation = new InstrumentationInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.instrumentation[i] = generateInstrumentationInfo(
                            p.instrumentation.get(i), flags);
                }
            }
        }
        boolean hasGetSignatureFlag = (flags & PackageManager.GET_SIGNATURES) != 0;
        boolean hasGetSigningInfoFlag = BuildCompat.isPie() && (flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0;

        if (hasGetSignatureFlag || hasGetSigningInfoFlag) {
            int N = (p.mSignatures != null) ? p.mSignatures.length : 0;
            if (N > 0) {
                pi.signatures = new Signature[N];
                System.arraycopy(p.mSignatures, 0, pi.signatures, 0, N);
            }
        }
        if (hasGetSigningInfoFlag) {
            pi.signingInfo = p.signingInfo;
        }
        return pi;
    }

    private static boolean copyNeeded(int flags, VPackage p,
                                      PackageUserState state, Bundle metaData, int userId) {
        if (!state.installed || state.hidden) {
            return true;
        }
        return (flags & PackageManager.GET_META_DATA) != 0
                && (metaData != null || p.mAppMetaData != null);
    }

    public static ApplicationInfo generateApplicationInfo(VPackage p, int flags,
                                                          PackageUserState state, int userId) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }

        final boolean copy = copyNeeded(flags, p, state, null, userId);
        // Make shallow copy when metadata/libraries must be adjusted safely.
        ApplicationInfo ai = copy ? new ApplicationInfo(p.applicationInfo) : p.applicationInfo;
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = p.mAppMetaData;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ai.splitNames = p.splitNames != null ? p.splitNames.clone() : new String[0];
        }
        initApplicationAsUser(ai, userId);
        return ai;
    }


    public static ActivityInfo generateActivityInfo(VPackage.ActivityComponent a, int flags,
                                                    PackageUserState state, int userId) {
        if (a == null) return null;
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo(a.info);
        if ((flags & PackageManager.GET_META_DATA) != 0
                && (a.metaData != null)) {
            ai.metaData = a.metaData;
        }
        ai.applicationInfo = generateApplicationInfo(a.owner, flags, state, userId);
        return ai;
    }

    public static ServiceInfo generateServiceInfo(VPackage.ServiceComponent s, int flags,
                                                  PackageUserState state, int userId) {
        if (s == null) return null;
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        ServiceInfo si = new ServiceInfo(s.info);
        // Make shallow copies so we can store the metadata safely
        if ((flags & PackageManager.GET_META_DATA) != 0 && s.metaData != null) {
            si.metaData = s.metaData;
        }
        si.applicationInfo = generateApplicationInfo(s.owner, flags, state, userId);
        return si;
    }

    public static ProviderInfo generateProviderInfo(VPackage.ProviderComponent p, int flags,
                                                    PackageUserState state, int userId) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo(p.info);
        if ((flags & PackageManager.GET_META_DATA) != 0
                && (p.metaData != null)) {
            pi.metaData = p.metaData;
        }

        if ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = generateApplicationInfo(p.owner, flags, state, userId);
        return pi;
    }

    public static InstrumentationInfo generateInstrumentationInfo(
            VPackage.InstrumentationComponent i, int flags) {
        if (i == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return i.info;
        }
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        ii.metaData = i.metaData;
        return ii;
    }

    public static PermissionInfo generatePermissionInfo(
            VPackage.PermissionComponent p, int flags) {
        if (p == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return p.info;
        }
        PermissionInfo pi = new PermissionInfo(p.info);
        pi.metaData = p.metaData;
        return pi;
    }

    public static PermissionGroupInfo generatePermissionGroupInfo(
            VPackage.PermissionGroupComponent pg, int flags) {
        if (pg == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return pg.info;
        }
        PermissionGroupInfo pgi = new PermissionGroupInfo(pg.info);
        pgi.metaData = pg.metaData;
        return pgi;
    }

    private static boolean checkUseInstalledOrHidden(PackageUserState state, int flags) {
        //noinspection deprecation
        return (state.installed && !state.hidden)
                || (flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0;
    }

}
