package org.apptwin.gms.fixture;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FixtureMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL = "fixture_external_messages";

    @Override
    public void onNewToken(String token) {
        ProbeStore store = new ProbeStore(this);
        TokenIsolationRecord record = new TokenIsolationRecord(
                getPackageName(),
                store.getOrCreateSentinel(),
                "firebase-callback",
                fingerprint(token));
        store.save(new ProbeResult(
                ProbeId.FCM_LOCAL_CONTRACT,
                ProbeStatus.EXTERNAL_UNTESTED,
                "token_callback_observed;scope=" + record.isolationKey()
                        + ";fingerprint=" + record.tokenFingerprint,
                System.currentTimeMillis()));
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        ProbeStore store = new ProbeStore(this);
        String sentinel = store.getOrCreateSentinel();
        boolean messageIdPresent = message.getMessageId() != null;
        store.save(new ProbeResult(
                ProbeId.FCM_LOCAL_CONTRACT,
                ProbeStatus.EXTERNAL_UNTESTED,
                "message_callback_observed;message_id_present=" + messageIdPresent
                        + ";notification_route_sentinel=" + sentinel,
                System.currentTimeMillis()));
        postRoutedNotification(sentinel);
    }

    private void postRoutedNotification(String sentinel) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Fixture external messages", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent target = new Intent(this, MainActivity.class)
                .putExtra("notification_group_sentinel", sentinel)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, sentinel.hashCode(), target, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("FCM fixture callback")
                .setContentText("Tap to verify routing to this Group sentinel")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        manager.notify("fcm-fixture@" + sentinel, 1, notification);
    }

    private static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                result.append(String.format("%02x", digest[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
