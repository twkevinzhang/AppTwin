package org.maskaccounts.runtime

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import com.lody.virtual.client.ipc.VActivityManager

/** Starts the minimum device-registration work needed before a Google guest opens account UI. */
internal object GoogleRuntimeBootstrap {
    private const val TAG = "GoogleRuntimeBootstrap"
    internal const val GMS_PACKAGE = "com.google.android.gms"
    internal const val GMS_INTENT_OPERATION_SERVICE =
        "com.google.android.gms.chimera.GmsIntentOperationService"
    internal const val CHECKIN_ACTION = "com.google.android.gms.checkin.CHECKIN_START_ACTION"
    internal const val CHECKIN_OPERATION_CATEGORY =
        "targeted_intent_op_prefix:.checkin.CheckinIntentOperation"

    fun prewarmCheckin(environmentId: Int) {
        val component = VActivityManager.get().startService(
            null,
            checkinIntent(),
            null,
            environmentId,
        )
        check(component != null) { "無法啟動虛擬 Google Checkin" }
        Log.i(TAG, "google-checkin-prewarm-started environment=$environmentId component=$component")
    }

    internal fun checkinIntent(): Intent = Intent(CHECKIN_ACTION)
        .setPackage(GMS_PACKAGE)
        .setComponent(ComponentName(GMS_PACKAGE, GMS_INTENT_OPERATION_SERVICE))
        .addCategory(CHECKIN_OPERATION_CATEGORY)
}
