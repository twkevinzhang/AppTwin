package org.maskaccounts.runtime

/** Packages that have completed MaskAccounts runtime bring-up and device acceptance. */
object CloneRuntimeSupport {
    const val LINE_PACKAGE = "jp.naver.line.android"
    const val SHOPEE_PACKAGE = "com.shopee.tw"
    private const val SHOPEE_LOGIN_ACTIVITY =
        "com.shopee.app.ui.auth2.login.origin.LoginActivity_"

    private val supportedPackages = setOf(
        LINE_PACKAGE,
        SHOPEE_PACKAGE,
    )

    fun canLaunch(packageName: String): Boolean = packageName in supportedPackages

    fun loginActivity(packageName: String): String? = when (packageName) {
        SHOPEE_PACKAGE -> SHOPEE_LOGIN_ACTIVITY
        else -> null
    }
}
