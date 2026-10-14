package org.apptwin.fixture

import android.accounts.AccountManager
import android.app.Activity
import java.io.File
import org.json.JSONObject

/** Real framework account picker entry, with synthetic fixture accounts only. */
class OwnedAccountPickerProbe(private val activity: Activity) {
    private val input = JSONObject(File(activity.filesDir, ENABLE_FILE).readText())
    private var callbacks = 0

    fun start() {
        val types = arrayOf(input.optString("accountType", "org.apptwin.fixture"))
        val intent = AccountManager.newChooseAccountIntent(null, null, types, false,
            "Owned fixture account boundary", null, null, null)
        intent.putExtra("userId", input.getInt("forgedUser"))
        activity.startActivityForResult(intent, REQUEST_CODE)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode != REQUEST_CODE) return
        callbacks++
        val result = JSONObject().put("requestId", input.getString("requestId"))
            .put("callbacks", callbacks).put("resultOk", resultCode == Activity.RESULT_OK)
            .put("cancelled", resultCode == Activity.RESULT_CANCELED)
            .put("hasAccount", data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) != null)
            .put("nameMatches", data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) == input.getString("expectedName"))
            .put("typeMatches", data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) == input.optString("accountType", "org.apptwin.fixture"))
        val output = File(activity.filesDir, RESULT_FILE)
        val temporary = File(output.path + ".tmp")
        temporary.writeText(result.toString())
        check(temporary.renameTo(output))
    }

    companion object {
        const val ENABLE_FILE = "enable-owned-account-picker-probe"
        const val RESULT_FILE = "owned-account-picker-outcome.json"
        const val REQUEST_CODE = 42372
    }
}
