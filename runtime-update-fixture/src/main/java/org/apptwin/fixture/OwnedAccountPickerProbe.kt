package org.apptwin.fixture

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.File
import org.json.JSONObject

/** Real framework account picker entry, with synthetic fixture accounts only. */
class OwnedAccountPickerProbe(private val activity: Activity) {
    private val input = JSONObject(File(activity.filesDir, ENABLE_FILE).readText())
    private var callbacks = 0

    fun start() {
        if (input.optBoolean("forwarded")) {
            activity.startActivityForResult(Intent(activity, OwnedAccountPickerTransientActivity::class.java), REQUEST_CODE)
        } else activity.startActivityForResult(chooseIntent(input), REQUEST_CODE)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode != REQUEST_CODE) return
        callbacks++
        val result = JSONObject().put("requestId", input.getString("requestId"))
            .put("callbacks", callbacks).put("requestCodeMatches", requestCode == REQUEST_CODE)
            .put("resultOk", resultCode == Activity.RESULT_OK)
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
        const val TRANSIENT_RESULT_FILE = "owned-account-picker-transient.json"
        fun chooseIntent(input: JSONObject): Intent {
            val types = arrayOf(input.optString("accountType", "org.apptwin.fixture"))
            return AccountManager.newChooseAccountIntent(null, null, types, false,
                "Owned fixture account boundary", null, null, null)
                .putExtra("userId", input.getInt("forgedUser"))
        }
    }
}

/** Mirrors a real guest login trampoline that forwards its result and immediately finishes. */
class OwnedAccountPickerTransientActivity : Activity() {
    private var requestId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = JSONObject(File(filesDir, OwnedAccountPickerProbe.ENABLE_FILE).readText())
        requestId = input.getString("requestId")
        startActivity(OwnedAccountPickerProbe.chooseIntent(input).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT))
        finish()
    }
    override fun onDestroy() {
        val output = File(filesDir, OwnedAccountPickerProbe.TRANSIENT_RESULT_FILE)
        val temporary = File(output.path + ".tmp")
        temporary.writeText(JSONObject().put("requestId", requestId).put("destroyed", true).toString())
        check(temporary.renameTo(output))
        super.onDestroy()
    }
}
