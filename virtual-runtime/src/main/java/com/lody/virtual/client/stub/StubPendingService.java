package com.lody.virtual.client.stub;

import com.lody.virtual.client.ipc.VActivityManager;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;


/**
 * @author Lody
 */

public class StubPendingService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // _VA_|_from_inner_ marked
        if (intent != null) {
            Intent realIntent = intent.getParcelableExtra("_VA_|_intent_");
            int userId = intent.getIntExtra("_VA_|_user_id_", 0);
            if (realIntent != null && PendingIntentUserGeneration.isCurrent(intent, userId)) {
                Intent replyIntent = PendingIntentRemoteInput.copyResults(intent, realIntent, "service");
                if (replyIntent != null) {
                    VActivityManager.get().startService(null, replyIntent, null, userId);
                }
            }
        }
        stopSelf();
        return START_NOT_STICKY;
    }
}
