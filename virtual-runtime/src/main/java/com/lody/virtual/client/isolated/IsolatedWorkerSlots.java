package com.lody.virtual.client.isolated;

import android.content.ComponentName;
import android.content.Context;

import com.lody.virtual.client.stub.StubIsolatedService;

/** Stable mapping between logical isolated owners and the finite manifest worker pool. */
public final class IsolatedWorkerSlots {
    public static final int SLOT_COUNT = 50;

    private IsolatedWorkerSlots() {
    }

    public static ComponentName component(Context context, int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("isolated worker slot out of range: " + slot);
        }
        return new ComponentName(context.getPackageName(),
                StubIsolatedService.class.getName() + "$C" + slot);
    }
}
