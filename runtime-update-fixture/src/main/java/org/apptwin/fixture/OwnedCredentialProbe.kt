package org.apptwin.fixture

import android.app.Activity
import android.credentials.CredentialManager
import android.credentials.CredentialOption
import android.credentials.GetCredentialException
import android.credentials.GetCredentialRequest
import android.credentials.GetCredentialResponse
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import java.io.File
import org.json.JSONObject

/** Exercises the real framework entry point; never persists credential contents. */
class OwnedCredentialProbe(private val activity: Activity) {
    private val cancellation = CancellationSignal()
    private var callbacks = 0
    private lateinit var requestId: String

    fun start() {
        val input = JSONObject(File(activity.filesDir, ENABLE_FILE).readText())
        requestId = input.getString("requestId")
        if (Build.VERSION.SDK_INT < 34) {
            record("unsupported-sdk", "")
            return
        }
        val unsupported = input.optBoolean("unsupportedOption")
        val type = if (unsupported) "org.apptwin.fixture.UNSUPPORTED" else GOOGLE_TYPE
        val data = Bundle().apply {
            putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_SERVER_CLIENT_ID", "owned-fixture.apps.googleusercontent.com")
            putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_NONCE", requestId)
            putBoolean("com.google.android.libraries.identity.googleid.BUNDLE_KEY_FILTER_BY_AUTHORIZED_ACCOUNTS", false)
        }
        val option = CredentialOption.Builder(type, data, Bundle(data))
            .setIsSystemProviderRequired(!unsupported)
            .build()
        val request = GetCredentialRequest.Builder(Bundle()).addCredentialOption(option).build()
        val manager = activity.getSystemService(CredentialManager::class.java)
        try {
            manager.getCredential(activity, request, cancellation, activity.mainExecutor,
                object : OutcomeReceiver<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) { record("unexpected-success", "") }
                    override fun onError(error: GetCredentialException) { record("error", error.type) }
                })
        } catch (error: RuntimeException) {
            record("exception", error.javaClass.simpleName)
        }
    }

    fun cancel() { cancellation.cancel() }

    private fun record(status: String, errorType: String) {
        callbacks++
        val result = JSONObject().put("requestId", requestId).put("status", status)
            .put("errorType", errorType).put("callbacks", callbacks)
        val output = File(activity.filesDir, RESULT_FILE)
        val temporary = File(output.path + ".tmp")
        temporary.writeText(result.toString())
        check(temporary.renameTo(output))
    }

    companion object {
        const val ENABLE_FILE = "enable-owned-credential-probe"
        const val RESULT_FILE = "owned-credential-outcome.json"
        const val GOOGLE_TYPE = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
    }
}
