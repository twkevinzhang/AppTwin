package com.lody.virtual.server.am;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.IInterface;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.Test;

import java.io.FileDescriptor;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ServiceRecordTest {

    @Test
    public void sameFilterIntentRequestsOnBindOnlyOnceBeforePublication() {
        // VActivityManagerService coalesces filterEquals intents into this one record.
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();

        assertTrue(binding.requestBindIfNeeded());
        assertFalse(binding.requestBindIfNeeded());
    }

    @Test
    public void publishedBinderAndSnapshotAreSharedByAllConnections() {
        IServiceConnection firstConnection = connection();
        IServiceConnection secondConnection = connection();
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();
        binding.addConnection(firstConnection);
        binding.addConnection(secondConnection);
        IBinder published = new FakeBinder();

        List<IServiceConnection> snapshot = binding.publish(published);

        assertSame(published, binding.binder);
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.contains(firstConnection));
        assertTrue(snapshot.contains(secondConnection));
        assertFalse(binding.requestBindIfNeeded());
    }

    @Test
    public void rebindIsConsumedOnceAndUnbindWaitsForLastConnection() {
        IServiceConnection firstConnection = connection();
        IServiceConnection secondConnection = connection();
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();
        binding.addConnection(firstConnection);
        binding.addConnection(secondConnection);

        assertTrue(binding.removeConnection(firstConnection));
        assertTrue(binding.hasConnections());
        assertTrue(binding.removeConnectionAndCheckIfLast(secondConnection));
        assertFalse(binding.hasConnections());

        binding.setDoRebind(true);
        assertTrue(binding.consumeDoRebind());
        assertFalse(binding.consumeDoRebind());
    }

    private static IServiceConnection connection() {
        return new IServiceConnection() {
            private final IBinder binder = new FakeBinder();

            @Override
            public void connected(ComponentName name, IBinder service) throws RemoteException {
            }

            @Override
            public IBinder asBinder() {
                return binder;
            }
        };
    }

    private static final class FakeBinder implements IBinder {
        @Override public String getInterfaceDescriptor() { return "test"; }
        @Override public boolean pingBinder() { return true; }
        @Override public boolean isBinderAlive() { return true; }
        @Override public IInterface queryLocalInterface(String descriptor) { return null; }
        @Override public void dump(FileDescriptor fd, String[] args) { }
        @Override public void dumpAsync(FileDescriptor fd, String[] args) { }
        @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) { return false; }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) { }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
    }
}
