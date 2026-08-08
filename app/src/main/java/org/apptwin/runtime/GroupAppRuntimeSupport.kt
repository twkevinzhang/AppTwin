package org.apptwin.runtime

import android.content.Intent

enum class RuntimeCompatibility {
    VERIFIED,
    EXPERIMENTAL,
    UNVERIFIED,
}

/** Device-test metadata for GroupApp compatibility. This does not gate launches. */
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
        else -> RuntimeCompatibility.UNVERIFIED
    }

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

/**
 * Play Store is a Group-owned service entry point, not a removable [GroupApp].
 *
 * Keep its launcher contract in one place so the UI, picker filtering, and runtime cannot drift
 * onto different package identities.
 */
object GroupPlayStoreLaunchContract {
    const val PACKAGE_NAME = GroupAppRuntimeSupport.GOOGLE_PLAY_STORE_PACKAGE

    val launcher = VirtualLauncherContract(
        packageName = PACKAGE_NAME,
        action = Intent.ACTION_MAIN,
        category = Intent.CATEGORY_LAUNCHER,
        flags = Intent.FLAG_ACTIVITY_NEW_TASK,
    )

    fun isReservedGroupService(packageName: String): Boolean = packageName == PACKAGE_NAME
}

data class VirtualLauncherContract(
    val packageName: String,
    val action: String,
    val category: String,
    val flags: Int,
)
