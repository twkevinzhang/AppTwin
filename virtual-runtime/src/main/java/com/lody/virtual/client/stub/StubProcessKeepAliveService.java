package com.lody.virtual.client.stub;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Makes a virtual service process visible to Android's process importance bookkeeping.
 *
 * <p>Virtual services run directly inside a stub process and therefore do not appear in the
 * system ActivityManager's service list. Android may otherwise reclaim the process while an
 * in-flight guest operation is still completing.</p>
 */
public abstract class StubProcessKeepAliveService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public static final class C0 extends StubProcessKeepAliveService {
    }
}
