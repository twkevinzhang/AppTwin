package org.apptwin.fixture

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Intent
import android.os.Bundle

/** Synthetic account authenticator. Never authenticates to an external service. */
open class FixtureAccountService : Service() {
    protected open val interactive = false
    override fun onBind(intent: Intent?): android.os.IBinder {
        android.util.Log.i("FixtureAccount", "service-bound")
        return object : AbstractAccountAuthenticator(this) {
        override fun editProperties(r: AccountAuthenticatorResponse?, type: String?) = Bundle()
        override fun addAccount(r: AccountAuthenticatorResponse?, type: String?, token: String?,
            features: Array<out String>?, options: Bundle?): Bundle {
            android.util.Log.i("FixtureAccount", "add-request interactive=$interactive type=$type")
            if (interactive) return Bundle().apply {
                putParcelable(AccountManager.KEY_INTENT, Intent(this@FixtureAccountService, FixtureAccountLoginActivity::class.java)
                    .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, r))
                android.util.Log.i("FixtureAccount", "add-intent-returned")
            }
            val account = Account("fixture-account", "org.apptwin.fixture")
            AccountManager.get(this@FixtureAccountService).addAccountExplicitly(account, null, null)
            return Bundle().apply {
                putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
            }
        }
        override fun confirmCredentials(r: AccountAuthenticatorResponse?, account: Account?, options: Bundle?) =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }
        override fun getAuthToken(r: AccountAuthenticatorResponse?, account: Account?, type: String?, options: Bundle?) = Bundle()
        override fun getAuthTokenLabel(type: String?) = "Synthetic fixture"
        override fun updateCredentials(r: AccountAuthenticatorResponse?, account: Account?, type: String?, options: Bundle?) = Bundle()
        override fun hasFeatures(r: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?) =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
        override fun getAccountRemovalAllowed(r: AccountAuthenticatorResponse?, account: Account?): Bundle {
            android.util.Log.i("FixtureAccount", "removal-enter")
            val proof = getSharedPreferences("fixture_account_proof", MODE_PRIVATE)
            proof.edit().putInt("removal_approved", proof.getInt("removal_approved", 0) + 1).commit()
            android.util.Log.i("FixtureAccount", "removal-approved")
            return Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }
        }
    }.iBinder
    }
}

class FixtureInteractiveAccountService : FixtureAccountService() {
    override val interactive = true
}
