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

    private val verifiedPackages = setOf(LINE_PACKAGE, SHOPEE_PACKAGE)
    fun compatibility(packageName: String): RuntimeCompatibility = when (packageName) {
        in verifiedPackages -> RuntimeCompatibility.VERIFIED
        else -> RuntimeCompatibility.UNVERIFIED
    }

    fun loginActivity(packageName: String): String? = when (packageName) {
        SHOPEE_PACKAGE -> SHOPEE_LOGIN_ACTIVITY
        else -> null
    }
}
