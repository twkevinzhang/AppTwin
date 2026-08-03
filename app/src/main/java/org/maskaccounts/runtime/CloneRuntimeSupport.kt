package org.maskaccounts.runtime

enum class RuntimeCompatibility {
    VERIFIED,
    EXPERIMENTAL,
    UNSUPPORTED,
}

/** Packages with an explicit, device-tested MaskAccounts launch policy. */
object CloneRuntimeSupport {
    const val LINE_PACKAGE = "jp.naver.line.android"
    const val SHOPEE_PACKAGE = "com.shopee.tw"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val MAPS_PACKAGE = "com.google.android.apps.maps"
    const val GOOGLE_SERVICES_FRAMEWORK_PACKAGE = "com.google.android.gsf"
    const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
    const val GOOGLE_PLAY_STORE_PACKAGE = "com.android.vending"
    private const val SHOPEE_LOGIN_ACTIVITY =
        "com.shopee.app.ui.auth2.login.origin.LoginActivity_"

    private val verifiedPackages = setOf(
        LINE_PACKAGE,
        SHOPEE_PACKAGE,
    )
    private val experimentalPackages = setOf(
        YOUTUBE_PACKAGE,
        MAPS_PACKAGE,
    )

    fun compatibility(packageName: String): RuntimeCompatibility = when (packageName) {
        in verifiedPackages -> RuntimeCompatibility.VERIFIED
        in experimentalPackages -> RuntimeCompatibility.EXPERIMENTAL
        else -> RuntimeCompatibility.UNSUPPORTED
    }

    fun canLaunch(packageName: String): Boolean =
        compatibility(packageName) != RuntimeCompatibility.UNSUPPORTED

    /** M1 Maps gets its own in-runtime user; this is not an Android system user/profile. */
    fun requiresDedicatedVirtualUser(packageName: String): Boolean = packageName == MAPS_PACKAGE

    fun loginActivity(packageName: String): String? = when (packageName) {
        SHOPEE_PACKAGE -> SHOPEE_LOGIN_ACTIVITY
        else -> null
    }

    /** Packages that must be visible to the guest before this app can initialise. */
    fun requiredPackages(packageName: String): List<String> = when (packageName) {
        YOUTUBE_PACKAGE,
        MAPS_PACKAGE,
        -> listOf(
            GOOGLE_SERVICES_FRAMEWORK_PACKAGE,
            GOOGLE_PLAY_SERVICES_PACKAGE,
            GOOGLE_PLAY_STORE_PACKAGE,
        )
        else -> emptyList()
    }
}
