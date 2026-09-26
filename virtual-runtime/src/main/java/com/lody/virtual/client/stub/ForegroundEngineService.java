package com.lody.virtual.client.stub;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** A visible guest Activity's importance dependency. No started work, FGS, or notification. */
public final class ForegroundEngineService extends Service {
    private final IBinder endpoint = new Binder();
    @Override public IBinder onBind(Intent intent) { return endpoint; }
}
