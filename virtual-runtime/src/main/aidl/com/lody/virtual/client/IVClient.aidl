// IVClient.aidl
package com.lody.virtual.client;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import com.lody.virtual.remote.PendingResultData;

interface IVClient {
    void scheduleReceiver(in String processName,in ComponentName component, in Intent intent, in PendingResultData resultData);
    void scheduleNewIntent(in String creator, in IBinder token, in Intent intent);
    void finishActivity(in IBinder token);
    IBinder createProxyService(in ComponentName component, in IBinder binder);
    IBinder acquireProviderClient(in ProviderInfo info);
    IBinder getAppThread();
    IBinder getToken();
    String getDebugInfo();
    void scheduleCreateService(in IBinder token, in ServiceInfo info, int processState);
    void scheduleBindService(in IBinder token, in IBinder bindToken, in Intent intent,
            boolean rebind, int processState, long bindSeq);
    void scheduleUnbindService(in IBinder token, in IBinder bindToken, in Intent intent);
    void scheduleServiceArgs(in IBinder token, boolean taskRemoved, int startId, int flags,
            in Intent intent);
    void scheduleStopService(in IBinder token);
}
