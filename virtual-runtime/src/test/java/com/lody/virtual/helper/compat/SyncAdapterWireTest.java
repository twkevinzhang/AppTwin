package com.lody.virtual.helper.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncAdapterWireTest {
    @Test public void androidNineInsertedUnsyncableBeforeStartAndCancel() {
        assertEquals(1, SyncAdapterWire.startTransaction(26));
        assertEquals(2, SyncAdapterWire.cancelTransaction(26));
        assertEquals(1, SyncAdapterWire.startTransaction(27));
        assertEquals(2, SyncAdapterWire.cancelTransaction(27));
        for (int sdk : new int[] {28, 31, 36, 37}) {
            assertEquals(2, SyncAdapterWire.startTransaction(sdk));
            assertEquals(3, SyncAdapterWire.cancelTransaction(sdk));
        }
    }
}
