package com.lody.virtual.client.isolated;

import android.os.Bundle;
import android.content.Intent;
import android.content.pm.ServiceInfo;

/** Bootstrap channel hosted by a real Android isolated-process service. */
interface IIsolatedGuestWorker {
    Bundle probe(String packageName, String serviceClassName, String nativeLibraryName,
            boolean loadNativeLibrary);
    Bundle createGuestService(in ServiceInfo serviceInfo);
    IBinder bindGuestService(in Intent intent, boolean rebind);
    boolean unbindGuestService(in Intent intent);
    int startGuestService(in Intent intent, int flags, int startId);
    void destroyGuestService();
}
