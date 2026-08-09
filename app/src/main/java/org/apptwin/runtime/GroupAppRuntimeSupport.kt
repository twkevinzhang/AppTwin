package org.apptwin.runtime

enum class RuntimeCompatibility {
    VERIFIED,
    UNVERIFIED,
}

/** Device-test metadata for GroupApp compatibility. This does not gate launches. */
object GroupAppRuntimeSupport {
    const val LINE_PACKAGE = "jp.naver.line.android"
    const val SHOPEE_PACKAGE = "com.shopee.tw"
    private const val SHOPEE_LOGIN_ACTIVITY =
        "com.shopee.app.ui.auth2.login.origin.LoginActivity_"

    private val deviceValidations = mapOf(
        LINE_PACKAGE to DeviceValidation(androidApi = 31, versionCode = 150_540_375L),
        SHOPEE_PACKAGE to DeviceValidation(androidApi = 31, versionCode = 37_927L),
    )

    fun compatibility(
        packageName: String,
        versionCode: Long,
        androidApi: Int,
    ): RuntimeCompatibility = if (
        deviceValidations[packageName] == DeviceValidation(androidApi, versionCode)
    ) {
        RuntimeCompatibility.VERIFIED
    } else {
        RuntimeCompatibility.UNVERIFIED
    }

    fun validation(packageName: String, versionCode: Long, androidApi: Int): DeviceValidation? =
        deviceValidations[packageName]?.takeIf {
            it.versionCode == versionCode && it.androidApi == androidApi
        }

    fun loginActivity(packageName: String): String? = when (packageName) {
        SHOPEE_PACKAGE -> SHOPEE_LOGIN_ACTIVITY
        else -> null
    }
}

data class DeviceValidation(val androidApi: Int, val versionCode: Long)
