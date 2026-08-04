package org.maskaccounts.runtime

enum class RuntimeCompatibility {
    VERIFIED,
    EXPERIMENTAL,
    UNSUPPORTED,
}

/** Packages with an explicit, device-tested GroupApp launch policy. */
object GroupAppRuntimeSupport {
    const val LINE_PACKAGE = "jp.naver.line.android"
    const val SHOPEE_PACKAGE = "com.shopee.tw"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val MAPS_PACKAGE = "com.google.android.apps.maps"
    const val GOOGLE_SERVICES_FRAMEWORK_PACKAGE = "com.google.android.gsf"
    const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
    const val GOOGLE_PLAY_STORE_PACKAGE = "com.android.vending"
    private const val SHOPEE_LOGIN_ACTIVITY =
        "com.shopee.app.ui.auth2.login.origin.LoginActivity_"

    private val verifiedPackages = setOf(LINE_PACKAGE, SHOPEE_PACKAGE)
    private val experimentalPackages = setOf(YOUTUBE_PACKAGE, MAPS_PACKAGE)

    fun compatibility(packageName: String): RuntimeCompatibility = when (packageName) {
        in verifiedPackages -> RuntimeCompatibility.VERIFIED
        in experimentalPackages -> RuntimeCompatibility.EXPERIMENTAL
        else -> RuntimeCompatibility.UNSUPPORTED
    }

    fun canLaunch(packageName: String): Boolean =
        compatibility(packageName) != RuntimeCompatibility.UNSUPPORTED

    fun loginActivity(packageName: String): String? = when (packageName) {
        SHOPEE_PACKAGE -> SHOPEE_LOGIN_ACTIVITY
        else -> null
    }

    /** Extra packages that must be visible before this GroupApp can initialise. */
    fun requiredPackages(packageName: String): List<String> = when (packageName) {
        YOUTUBE_PACKAGE,
        MAPS_PACKAGE,
        -> googlePackages
        else -> emptyList()
    }

    val googlePackages = listOf(
        GOOGLE_SERVICES_FRAMEWORK_PACKAGE,
        GOOGLE_PLAY_SERVICES_PACKAGE,
        GOOGLE_PLAY_STORE_PACKAGE,
    )
}
