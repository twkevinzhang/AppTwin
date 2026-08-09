package org.apptwin.microg.artifact

import java.util.Base64

enum class ProductionMicrogArtifactKind {
    GMS_CORE,
    COMPANION_STORE,
}

data class ProductionMicrogManifest(
    val kind: ProductionMicrogArtifactKind,
    val releaseId: String,
    /** Version of the reviewed GmsCore release which binds all artifacts into one release. */
    val releaseVersionName: String,
    /** Version declared by this individual APK. */
    val versionName: String,
    val packageName: String,
    val versionCode: Long,
    val apkFileName: String,
    val apkSha256: String,
    val realSignerSha256: String,
    val exposedCertificateDer: ByteArray,
    val exposedCertificateSha256: String,
    val splitApkSha256: Map<String, String> = emptyMap(),
)

data class ProductionMicrogReleaseManifest(
    val releaseId: String,
    val versionName: String,
    val artifacts: List<ProductionMicrogManifest>,
) {
    init {
        require(artifacts.map { it.kind }.toSet() == ProductionMicrogArtifactKind.entries.toSet()) {
            "Production microG release must contain exactly GmsCore and Companion Store"
        }
        require(artifacts.map { it.kind }.distinct().size == artifacts.size) {
            "Production microG release must not contain duplicate artifact kinds"
        }
        require(artifacts.map { it.packageName }.distinct().size == artifacts.size) {
            "Production microG release must not contain duplicate packages"
        }
        require(artifacts.all { it.releaseId == releaseId && it.releaseVersionName == versionName }) {
            "Every microG artifact must be bound to the same reviewed release"
        }
    }

    fun artifact(kind: ProductionMicrogArtifactKind): ProductionMicrogManifest =
        artifacts.single { it.kind == kind }
}

object PinnedMicrogRelease {
    const val RELEASE_ID = "microg-v0.3.15.250932"
    const val VERSION_NAME = "0.3.15.250932"
    const val REAL_SIGNER_SHA256 =
        "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"
    const val EXPOSED_CERTIFICATE_SHA256 =
        "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"

    const val PACKAGE_NAME = "com.google.android.gms"
    const val VERSION_CODE = 250932030L
    const val APK_FILE_NAME = "com.google.android.gms-250932030.apk"
    const val APK_SHA256 =
        "52597e77fd25fdd347574d0457ed1936a4b9561cf4c8d34e7ac8dd8191dfd4b9"
    const val OFFICIAL_DOWNLOAD_URL =
        "https://github.com/microg/GmsCore/releases/download/v0.3.15.250932/$APK_FILE_NAME"

    const val COMPANION_PACKAGE_NAME = "com.android.vending"
    const val COMPANION_VERSION_CODE = 84022630L
    const val COMPANION_VERSION_NAME = "0.3.15.40226"
    const val COMPANION_APK_FILE_NAME = "com.android.vending-84022630.apk"
    const val COMPANION_APK_SHA256 =
        "a973e0235a2829773a4faf36d235d5f703d1c04a2adff674ebaa535a2e78f937"
    const val COMPANION_OFFICIAL_DOWNLOAD_URL =
        "https://github.com/microg/GmsCore/releases/download/v0.3.15.250932/" +
            COMPANION_APK_FILE_NAME

    private const val EXPOSED_CERTIFICATE_DER_BASE64 =
        "MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfYwXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/CxURaSxaBvGdGDhfXE28LWuT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k//0zOHKrUiGYXtqw/A0LFFtqoZKFjnkCAQOjgdkwgdYwHQYDVR0OBBYEFMd9jMIhF1Ylmn/Tgt9r45jk14alMIGmBgNVHSMEgZ4wgZuAFMd9jMIhF1Ylmn/Tgt9r45jk14aloXikdjB0MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29vZ2xlIEluYy4xEDAOBgNVBAsTB0FuZHJvaWQxEDAOBgNVBAMTB0FuZHJvaWSCCQDC4IdGZEowjTAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBBAUAA4IBAQBt0lLO74UwLDYKqs6Tm8/yzKkEu116FmH4rkaymUIE0P9KaMftGlMexFlaYjzmB2OxZyl6euNXEsQH8gjwyxCUKRJNexBiGcCEyj6z+a1fuHHvkiaai+KL8W1EyNmgjmyy8AW7P+LLlkR+ho5zEHatRbM/YAnqGcFh5iZBqpknHf1SKMXFh4dd239FJ1jWYfbMDMy3NS5CTMQ2XFI1MvcyUTdZPErjQfTbQe3aDQsQcafEQPD+nqActifKZ0Np0IS9L9kR/wbNvyz6ENwPiTrjV2KRkEjH78ZMcUQXg0L3BYHJ3lc69Vs5Ddf9uUGGMYldX3WfMBEmh/9iFBDAaTCK"

    private val exposedCertificateDer: ByteArray
        get() = Base64.getDecoder().decode(EXPOSED_CERTIFICATE_DER_BASE64)

    val gmsCore: ProductionMicrogManifest
        get() = ProductionMicrogManifest(
            kind = ProductionMicrogArtifactKind.GMS_CORE,
            releaseId = RELEASE_ID,
            releaseVersionName = VERSION_NAME,
            versionName = VERSION_NAME,
            packageName = PACKAGE_NAME,
            versionCode = VERSION_CODE,
            apkFileName = APK_FILE_NAME,
            apkSha256 = APK_SHA256,
            realSignerSha256 = REAL_SIGNER_SHA256,
            exposedCertificateDer = exposedCertificateDer,
            exposedCertificateSha256 = EXPOSED_CERTIFICATE_SHA256,
        )

    val companionStore: ProductionMicrogManifest
        get() = ProductionMicrogManifest(
            kind = ProductionMicrogArtifactKind.COMPANION_STORE,
            releaseId = RELEASE_ID,
            releaseVersionName = VERSION_NAME,
            versionName = COMPANION_VERSION_NAME,
            packageName = COMPANION_PACKAGE_NAME,
            versionCode = COMPANION_VERSION_CODE,
            apkFileName = COMPANION_APK_FILE_NAME,
            apkSha256 = COMPANION_APK_SHA256,
            realSignerSha256 = REAL_SIGNER_SHA256,
            // Companion declares FAKE_PACKAGE_SIGNATURE too; this is consumed only by AppTwin's
            // two-package trusted virtual-PM allowlist, never by the physical PackageManager.
            exposedCertificateDer = exposedCertificateDer,
            exposedCertificateSha256 = EXPOSED_CERTIFICATE_SHA256,
        )

    val manifest: ProductionMicrogReleaseManifest
        get() = ProductionMicrogReleaseManifest(
            releaseId = RELEASE_ID,
            versionName = VERSION_NAME,
            artifacts = listOf(gmsCore, companionStore),
        )
}
