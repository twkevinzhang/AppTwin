package org.apptwin

import android.content.Context
import android.content.Intent

internal object GroupAppLaunchContract {
    const val ACTION_LAUNCH_GROUP_APP = "org.apptwin.action.LAUNCH_GROUP_APP"
    const val EXTRA_GROUP_ID = "org.apptwin.extra.GROUP_ID"
    const val EXTRA_PACKAGE_NAME = "org.apptwin.extra.PACKAGE_NAME"

    fun launchKey(groupId: String, packageName: String): String = "$groupId:$packageName"

    fun intent(context: Context, groupId: String, packageName: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_LAUNCH_GROUP_APP)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_GROUP_ID, groupId)
            .putExtra(EXTRA_PACKAGE_NAME, packageName)
}
