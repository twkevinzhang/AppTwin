package com.lody.virtual.server.secondary;

import com.lody.virtual.helper.compat.SyncAdapterWire;

final class VirtualSyncBridgePolicy {
    static boolean accepts(int callerPid, int serverPid, String descriptor, int transaction, int sdk) {
        return serverPid > 0 && callerPid == serverPid && "android.content.ISyncAdapter".equals(descriptor)
                && (transaction == SyncAdapterWire.startTransaction(sdk) || transaction == SyncAdapterWire.cancelTransaction(sdk));
    }
}
