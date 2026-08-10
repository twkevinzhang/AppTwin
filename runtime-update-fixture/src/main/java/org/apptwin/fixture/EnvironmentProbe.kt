package org.apptwin.fixture

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.webkit.WebView
import java.io.File
import java.security.MessageDigest

/**
 * A deliberately privacy-bounded view of the environment visible to this guest process.
 *
 * Raw paths, Android IDs, and /proc contents never leave [collect]. The resulting snapshot
 * contains only explicitly allow-listed values, classifications, hashes, and booleans.
 */
object EnvironmentProbe {
    @SuppressLint("HardwareIds") // The value is immediately one-way hashed and is never retained or emitted.
    fun collect(context: Context): EnvironmentProbeSnapshot {
        val packageManager = context.packageManager
        val guestPackage = context.packageName
        val processUid = Process.myUid()
        val applicationInfo = context.applicationInfo
        val uidPackages = runCatching {
            packageManager.getPackagesForUid(processUid)?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val nonGuestUidPackages = uidPackages.filterTo(linkedSetOf()) { it != guestPackage }
        val hostCandidates = buildSet {
            addAll(nonGuestUidPackages)
            EnvironmentProbeLogic.inferOwningPackage(applicationInfo.dataDir)?.let(::add)
            EnvironmentProbeLogic.inferOwningPackage(applicationInfo.sourceDir)?.let(::add)
            remove(guestPackage)
        }
        val androidIdHash = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.let(EnvironmentProbeLogic::sha256Prefix)
        val installSource = readInstallSource(packageManager, guestPackage)
        val procRealUid = EnvironmentProbeLogic.readProcRealUid(
            runCatching { File("/proc/self/status").readText() }.getOrNull(),
        )

        return EnvironmentProbeSnapshot(
            packageName = guestPackage,
            processUid = processUid,
            packageUid = applicationInfo.uid,
            processPackageUidConsistent = processUid == applicationInfo.uid,
            processProcUidConsistent = procRealUid == null || processUid == procRealUid,
            packageProcUidConsistent = procRealUid == null || applicationInfo.uid == procRealUid,
            dataDirOwner = EnvironmentProbeLogic.classifyPath(
                applicationInfo.dataDir,
                guestPackage,
                hostCandidates,
            ),
            sourceDirOwner = EnvironmentProbeLogic.classifyPath(
                applicationInfo.sourceDir,
                guestPackage,
                hostCandidates,
            ),
            uidPackagesContainsGuest = guestPackage in uidPackages,
            uidPackagesContainsHost = nonGuestUidPackages.isNotEmpty(),
            androidIdSha256Prefix = androidIdHash,
            buildManufacturer = Build.MANUFACTURER,
            buildBrand = Build.BRAND,
            buildModel = Build.MODEL,
            buildDevice = Build.DEVICE,
            buildProduct = Build.PRODUCT,
            buildSdk = Build.VERSION.SDK_INT,
            webViewProvider = runCatching {
                WebView.getCurrentWebViewPackage()?.packageName
            }.getOrNull(),
            gmsPackageAvailable = isEnabledPackageAvailable(packageManager, GMS_PACKAGE),
            installerPackage = installSource.first,
            initiatingPackage = installSource.second,
            selfSignatureSha256Prefix = readSelfSignatureHash(packageManager, guestPackage),
            procCmdlineOwner = EnvironmentProbeLogic.classifyCmdline(
                runCatching { File("/proc/self/cmdline").readText() }.getOrNull(),
                guestPackage,
                hostCandidates,
            ),
            procMapsLeaksHostPackage = EnvironmentProbeLogic.fileContainsAnyToken(
                File("/proc/self/maps"),
                hostCandidates,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun readInstallSource(
        packageManager: PackageManager,
        packageName: String,
    ): Pair<String?, String?> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).let {
                it.installingPackageName to it.initiatingPackageName
            }
        } else {
            packageManager.getInstallerPackageName(packageName) to null
        }
    }.getOrDefault(null to null)

    private fun isEnabledPackageAvailable(
        packageManager: PackageManager,
        packageName: String,
    ): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(packageName, 0).enabled
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun readSelfSignatureHash(
        packageManager: PackageManager,
        packageName: String,
    ): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            packageInfo.signingInfo?.let { signingInfo ->
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            }.orEmpty()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        signatures
            .map { EnvironmentProbeLogic.sha256Prefix(it.toByteArray()) }
            .sorted()
            .firstOrNull()
    }.getOrNull()

    private const val GMS_PACKAGE = "com.google.android.gms"
}

enum class PathOwner {
    GUEST,
    HOST,
    OTHER,
    UNAVAILABLE,
}

data class EnvironmentProbeSnapshot(
    val packageName: String,
    val processUid: Int,
    val packageUid: Int,
    val processPackageUidConsistent: Boolean,
    val processProcUidConsistent: Boolean,
    val packageProcUidConsistent: Boolean,
    val dataDirOwner: PathOwner,
    val sourceDirOwner: PathOwner,
    val uidPackagesContainsGuest: Boolean,
    val uidPackagesContainsHost: Boolean,
    val androidIdSha256Prefix: String?,
    val buildManufacturer: String,
    val buildBrand: String,
    val buildModel: String,
    val buildDevice: String,
    val buildProduct: String,
    val buildSdk: Int,
    val webViewProvider: String?,
    val gmsPackageAvailable: Boolean,
    val installerPackage: String?,
    val initiatingPackage: String?,
    val selfSignatureSha256Prefix: String?,
    val procCmdlineOwner: PathOwner,
    val procMapsLeaksHostPackage: Boolean,
) {
    /** A single-line payload suitable for both the UI, a file, and `adb logcat`. */
    fun toJson(): String = buildString {
        append('{')
        val fields = listOf(
            "probeVersion" to "1",
            "packageName" to packageName,
            "processUid" to processUid,
            "packageUid" to packageUid,
            "processPackageUidConsistent" to processPackageUidConsistent,
            "processProcUidConsistent" to processProcUidConsistent,
            "packageProcUidConsistent" to packageProcUidConsistent,
            "dataDirOwner" to dataDirOwner.name,
            "sourceDirOwner" to sourceDirOwner.name,
            "uidPackagesContainsGuest" to uidPackagesContainsGuest,
            "uidPackagesContainsHost" to uidPackagesContainsHost,
            "androidIdSha256Prefix" to androidIdSha256Prefix,
            "buildManufacturer" to buildManufacturer,
            "buildBrand" to buildBrand,
            "buildModel" to buildModel,
            "buildDevice" to buildDevice,
            "buildProduct" to buildProduct,
            "buildSdk" to buildSdk,
            "webViewProvider" to webViewProvider,
            "gmsPackageAvailable" to gmsPackageAvailable,
            "installerPackage" to installerPackage,
            "initiatingPackage" to initiatingPackage,
            "selfSignatureSha256Prefix" to selfSignatureSha256Prefix,
            "procCmdlineOwner" to procCmdlineOwner.name,
            "procMapsLeaksHostPackage" to procMapsLeaksHostPackage,
        )
        fields.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendJsonString(key)
            append(':')
            when (value) {
                null -> append("null")
                is Number, is Boolean -> append(value)
                else -> appendJsonString(value.toString())
            }
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

/** Pure helpers kept separate so privacy guarantees can be exercised by local unit tests. */
object EnvironmentProbeLogic {
    private const val HASH_PREFIX_LENGTH = 12
    private const val MAX_PROC_LINES = 50_000
    private val dataPackagePattern = Regex(
        "^/data/(?:data|user(?:_de)?/\\d+)/([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)(?:/|$)",
    )
    private val appPackagePattern = Regex(
        "^/data/app/(?:~~[^/]+/)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)(?:-[^/]*)?(?:/|$)",
    )

    fun readProcRealUid(status: String?): Int? {
        if (status.isNullOrBlank()) return null
        val uidLine = status.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return null
        return uidLine.removePrefix("Uid:")
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.toIntOrNull()
    }

    fun sha256Prefix(value: String): String = sha256Prefix(value.toByteArray(Charsets.UTF_8))

    fun sha256Prefix(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
        .take(HASH_PREFIX_LENGTH)

    fun inferOwningPackage(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return dataPackagePattern.find(path)?.groupValues?.get(1)
            ?: appPackagePattern.find(path)?.groupValues?.get(1)
    }

    fun classifyPath(
        path: String?,
        guestPackage: String,
        hostCandidates: Set<String>,
    ): PathOwner {
        if (path.isNullOrBlank()) return PathOwner.UNAVAILABLE
        val inferredOwner = inferOwningPackage(path)
        return when {
            inferredOwner == guestPackage -> PathOwner.GUEST
            inferredOwner != null && inferredOwner in hostCandidates -> PathOwner.HOST
            pathContainsPackage(path, guestPackage) -> PathOwner.GUEST
            hostCandidates.any { pathContainsPackage(path, it) } -> PathOwner.HOST
            else -> PathOwner.OTHER
        }
    }

    fun textContainsAnyToken(text: String, tokens: Set<String>): Boolean =
        tokens.any { token -> token.isNotBlank() && text.contains(token) }

    fun fileContainsAnyToken(file: File, tokens: Set<String>): Boolean {
        if (tokens.isEmpty()) return false
        return runCatching {
            file.bufferedReader().useLines { lines ->
                lines.take(MAX_PROC_LINES).any { textContainsAnyToken(it, tokens) }
            }
        }.getOrDefault(false)
    }

    fun classifyCmdline(
        rawCmdline: String?,
        guestPackage: String,
        hostCandidates: Set<String>,
    ): PathOwner {
        val processName = rawCmdline
            ?.substringBefore('\u0000')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return PathOwner.UNAVAILABLE
        return when {
            processName == guestPackage || processName.startsWith("$guestPackage:") -> PathOwner.GUEST
            hostCandidates.any { processName == it || processName.startsWith("$it:") } -> PathOwner.HOST
            else -> PathOwner.OTHER
        }
    }

    private fun pathContainsPackage(path: String, packageName: String): Boolean =
        path.contains("/$packageName/") ||
            path.contains("/$packageName-") ||
            path.endsWith("/$packageName")
}
