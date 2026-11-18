package org.apptwin.fixture

import android.app.admin.DeviceAdminReceiver

/** Policy-less admin used only to observe how a clone's device-admin request is handled. */
class FixtureAdminReceiver : DeviceAdminReceiver()
