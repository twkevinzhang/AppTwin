// IVClient.aidl
package com.lody.virtual.client;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import com.lody.virtual.remote.PendingResultData;

interface IVClient {
    oneway void scheduleReceiver(in String processName, in ComponentName component,
            in Intent intent, in PendingResultData resultData, long dispatchToken,
            long processGeneration);
    oneway void cancelReceiver(long dispatchToken, long processGeneration);
    oneway void scheduleNewIntent(in String creator, in IBinder token, in Intent intent,
            long processGeneration);
    oneway void finishActivity(in IBinder token, long processGeneration);
    IBinder createProxyService(in ComponentName component, in IBinder binder);
    IBinder acquireProviderClient(in ProviderInfo info);
    IBinder getAppThread();
    IBinder getToken();
    String getDebugInfo();
    oneway void scheduleCreateService(in IBinder token, in ServiceInfo info, int processState,
            long processGeneration);
    oneway void scheduleBindService(in IBinder token, in IBinder bindToken, in Intent intent,
            boolean rebind, int processState, long bindSeq, long processGeneration);
    oneway void scheduleUnbindService(in IBinder token, in IBinder bindToken, in Intent intent,
            long processGeneration);
    oneway void scheduleServiceArgs(in IBinder token, boolean taskRemoved, int startId, int flags,
            in Intent intent, long processGeneration);
    oneway void scheduleStopService(in IBinder token, long processGeneration);
}
