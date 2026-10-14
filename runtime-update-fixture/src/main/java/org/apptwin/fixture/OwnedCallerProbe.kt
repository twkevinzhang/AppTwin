package org.apptwin.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.File
import org.json.JSONObject

/** Calls the real Activity API across a guest startActivityForResult boundary. */
class OwnedCallerProbe(private val activity: Activity) {
    private val requestId = JSONObject(File(activity.filesDir, ENABLE_FILE).readText()).getString("requestId")
    private var callbacks = 0

    fun start() {
        activity.startActivityForResult(Intent(activity, OwnedCallerProbeActivity::class.java)
            .putExtra("requestId", requestId), REQUEST_CODE)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE) return
        callbacks++
        val result = JSONObject().put("requestId", requestId).put("callbacks", callbacks)
            .put("resultOk", resultCode == Activity.RESULT_OK)
            .put("requestMatches", data?.getStringExtra("requestId") == requestId)
            .put("callingPackageMatches", data?.getBooleanExtra("callingPackageMatches", false) == true)
            .put("callingActivityPackageMatches", data?.getBooleanExtra("callingActivityPackageMatches", false) == true)
            .put("callingActivityClassMatches", data?.getBooleanExtra("callingActivityClassMatches", false) == true)
        val file = File(activity.filesDir, RESULT_FILE)
        val temporary = File(file.path + ".tmp")
        temporary.writeText(result.toString())
        check(temporary.renameTo(file))
    }

    companion object {
        const val ENABLE_FILE = "enable-owned-caller-probe"
        const val RESULT_FILE = "owned-caller-outcome.json"
        const val REQUEST_CODE = 42371
    }
}

class OwnedCallerProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingActivity
        val result = Intent().putExtra("requestId", intent.getStringExtra("requestId"))
            .putExtra("callingPackageMatches", callingPackage == packageName)
            .putExtra("callingActivityPackageMatches", caller?.packageName == packageName)
            .putExtra("callingActivityClassMatches", caller?.className == FixtureActivity::class.java.name)
        setResult(RESULT_OK, result)
        finish()
    }
}
