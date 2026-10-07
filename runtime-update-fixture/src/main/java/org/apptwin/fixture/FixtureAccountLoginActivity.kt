package org.apptwin.fixture

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Delayed synthetic login: account state changes only after the visible completion button. */
class FixtureAccountLoginActivity : Activity() {
    private var response: AccountAuthenticatorResponse? = null
    private var terminal = false
    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.i("FixtureAccount", "login-create-entered")
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val supplied = intent.getParcelableExtra<AccountAuthenticatorResponse>(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
        response = supplied
        supplied?.onRequestContinued()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@FixtureAccountLoginActivity).apply { text = "AppTwin synthetic account login" })
            addView(Button(this@FixtureAccountLoginActivity).apply {
                isAllCaps = false
                text = "Complete fixture login"
                setOnClickListener {
                    if (terminal) return@setOnClickListener
                    terminal = true
                    val account = Account("fixture-interactive-account", "org.apptwin.fixture.interactive")
                    val added = AccountManager.get(this@FixtureAccountLoginActivity).addAccountExplicitly(account, null, null)
                    android.util.Log.i("FixtureAccount", "login-terminal added=$added")
                    if (added) response?.onResult(Bundle().apply {
                        putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                        putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
                    }) else response?.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "Fixture account already exists")
                    finish()
                }
            })
            addView(Button(this@FixtureAccountLoginActivity).apply {
                isAllCaps = false
                text = "Cancel fixture login"
                setOnClickListener { cancelLogin(); finish() }
            })
        })
        window.decorView.post { android.util.Log.i("FixtureAccount", "login-content-visible") }
    }
    private fun cancelLogin() {
        if (terminal) return
        terminal = true
        android.util.Log.i("FixtureAccount", "login-terminal canceled=true")
        response?.onError(AccountManager.ERROR_CODE_CANCELED, "Fixture login canceled")
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { cancelLogin(); super.onBackPressed() }
    override fun onDestroy() {
        if (isFinishing) cancelLogin()
        super.onDestroy()
    }
}
