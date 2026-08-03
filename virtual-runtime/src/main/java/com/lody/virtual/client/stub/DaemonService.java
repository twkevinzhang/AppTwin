package com.lody.virtual.client.stub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;

import java.io.File;


/**
 * @author Lody
 *
 */
public class DaemonService extends Service {

    private static final int NOTIFY_ID = 1001;
	private static final String NOTIFICATION_CHANNEL_ID = "virtual_runtime_daemon";

	static boolean showNotification = true;

	public static void startup(Context context) {
		File flagFile = context.getFileStreamPath(Constants.NO_NOTIFICATION_FLAG);
		showNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				|| Build.VERSION.SDK_INT < 25
				|| !flagFile.exists();

		Intent intent = new Intent(context, DaemonService.class);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.startForegroundService(intent);
		} else {
			context.startService(intent);
		}
		if (VirtualCore.get().isServerProcess()) {
			// PrivilegeAppOptimizer.notifyBootFinish();
			DaemonJobService.scheduleJob(context);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		startup(this);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (!showNotification) {
			return;
		}
		startForeground(NOTIFY_ID, createForegroundNotification());
	}

	private Notification createForegroundNotification() {
		CharSequence applicationLabel = getApplicationInfo().loadLabel(getPackageManager());
		if (applicationLabel == null || applicationLabel.length() == 0) {
			applicationLabel = getPackageName();
		}

		Notification.Builder builder;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(
					NOTIFICATION_CHANNEL_ID,
					applicationLabel,
					NotificationManager.IMPORTANCE_LOW);
			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			if (notificationManager != null) {
				notificationManager.createNotificationChannel(channel);
			}
			builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
		} else {
			builder = new Notification.Builder(this)
					.setPriority(Notification.PRIORITY_LOW);
		}

		return builder
				.setSmallIcon(android.R.drawable.stat_notify_sync)
				.setContentTitle(applicationLabel)
				.setCategory(Notification.CATEGORY_SERVICE)
				.setOngoing(true)
				.setShowWhen(false)
				.build();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

}
