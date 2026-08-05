package com.lody.virtual.server.am;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.IInterface;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.Test;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
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
    public void duplicateConnectionIsRegisteredOnlyOnce() {
        FakeBinder connectionBinder = new FakeBinder();
        IServiceConnection connection = connection(connectionBinder);
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();

        binding.addConnection(connection);
        binding.addConnection(connection);

        assertEquals(1, binding.snapshotConnections().size());
        assertEquals(1, connectionBinder.linkCount);
    }

    @Test
    public void publishedBinderAndSnapshotAreSharedByAllConnections() {
        IServiceConnection firstConnection = connection();
        IServiceConnection secondConnection = connection();
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();
        binding.addConnection(firstConnection);
        binding.addConnection(secondConnection);
        IBinder published = new FakeBinder();

        assertTrue(binding.requestBindIfNeeded());
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

    @Test
    public void pendingBindCanBeCancelledOnlyAfterLastConnectionLeaves() {
        IServiceConnection connection = connection();
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();
        binding.addConnection(connection);

        assertTrue(binding.requestBindIfNeeded());
        assertFalse(binding.cancelPendingBindIfNoConnections());
        assertTrue(binding.removeConnectionAndCheckIfLast(connection));
        assertTrue(binding.cancelPendingBindIfNoConnections());
        assertTrue(binding.publish(new FakeBinder()).isEmpty());
        assertTrue(binding.requestBindIfNeeded());
    }

    @Test
    public void latePublishFromDifferentGenerationIsIgnored() {
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord(7);
        IBinder staleBinder = new FakeBinder();

        assertTrue(binding.requestBindIfNeeded());
        assertTrue(binding.publish(6, staleBinder).isEmpty());
        assertFalse(binding.binder == staleBinder);

        IBinder currentBinder = new FakeBinder();
        binding.publish(7, currentBinder);
        assertSame(currentBinder, binding.binder);
    }

    @Test
    public void retiredServiceRejectsCreateAndLatePublish() {
        ServiceRecord service = new ServiceRecord(11);
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord(11);
        service.bindings.add(binding);

        assertTrue(service.markCreateScheduled());
        assertFalse(service.markCreateScheduled());
        assertTrue(service.acceptsCallback(11));
        assertTrue(service.retire());
        assertFalse(service.retire());
        assertTrue(service.isRetired());
        assertFalse(service.acceptsCallback(11));
        assertFalse(service.markCreateScheduled());
        assertTrue(binding.publish(11, new FakeBinder()).isEmpty());
        assertTrue(binding.isRetired());
    }

    @Test
    public void connectionDeathIsDelegatedAfterRemoval() {
        FakeBinder firstBinder = new FakeBinder();
        FakeBinder finalBinder = new FakeBinder();
        IServiceConnection firstConnection = connection(firstBinder);
        IServiceConnection finalConnection = connection(finalBinder);
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();
        List<Boolean> lastConnectionValues = new ArrayList<>();
        binding.setConnectionDeathCallback((record, connection, lastConnection) -> {
            assertSame(binding, record);
            assertFalse(record.containConnection(connection));
            lastConnectionValues.add(lastConnection);
        });
        binding.addConnection(firstConnection);
        binding.addConnection(finalConnection);

        firstBinder.die();
        finalBinder.die();

        assertEquals(2, lastConnectionValues.size());
        assertFalse(lastConnectionValues.get(0));
        assertTrue(lastConnectionValues.get(1));
        assertFalse(binding.hasConnections());
    }

    @Test
    public void quickRebindWaitsForUnbindResultAndChoosesRebindOrBind() {
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();
        IServiceConnection first = connection();
        binding.addConnection(first);
        assertTrue(binding.requestBindIfNeeded());
        binding.publish(new FakeBinder());

        assertTrue(binding.removeConnectionAndCheckIfLast(first));
        assertTrue(binding.beginUnbindIfNeeded());
        IServiceConnection quickClient = connection();
        binding.addConnection(quickClient);
        assertTrue(binding.isUnbindInFlight());
        assertFalse(binding.requestBindIfNeeded());
        assertEquals(ServiceRecord.IntentBindRecord.UnbindResult.REBIND,
                binding.finishUnbind(true));
        assertTrue(binding.hasPublishedBinder());

        assertTrue(binding.removeConnectionAndCheckIfLast(quickClient));
        assertTrue(binding.beginUnbindIfNeeded());
        IServiceConnection freshClient = connection();
        binding.addConnection(freshClient);
        assertEquals(ServiceRecord.IntentBindRecord.UnbindResult.BIND,
                binding.finishUnbind(false));
        assertFalse(binding.hasPublishedBinder());
        assertTrue(binding.shouldDispatchBind());
    }

    @Test
    public void retireUnlinksAndClearsConnections() {
        FakeBinder connectionBinder = new FakeBinder();
        IServiceConnection connection = connection(connectionBinder);
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();
        List<Boolean> callbacks = new ArrayList<>();
        binding.setConnectionDeathCallback((record, dead, last) -> callbacks.add(last));
        binding.addConnection(connection);

        binding.retire();
        connectionBinder.die();

        assertFalse(binding.hasConnections());
        assertEquals(1, connectionBinder.unlinkCount);
        assertTrue(callbacks.isEmpty());
    }

    @Test
    public void bindTokenIsStableAcrossBindAndRebindSequences() {
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord(31);
        IBinder bindToken = binding.getBindToken();

        assertSame(bindToken, binding.getBindToken());
        assertEquals(1L, binding.nextBindSequence());
        assertEquals(2L, binding.nextBindSequence());
        assertSame(bindToken, binding.getBindToken());
    }

    @Test
    public void clonedAccountsUseIndependentBindTokensAndRetirement() {
        ServiceRecord firstAccount = new ServiceRecord(41);
        ServiceRecord.IntentBindRecord firstBinding =
                new ServiceRecord.IntentBindRecord(41);
        firstAccount.bindings.add(firstBinding);
        ServiceRecord secondAccount = new ServiceRecord(42);
        ServiceRecord.IntentBindRecord secondBinding =
                new ServiceRecord.IntentBindRecord(42);
        secondAccount.bindings.add(secondBinding);

        assertNotSame(firstBinding.getBindToken(), secondBinding.getBindToken());
        assertSame(firstBinding,
                firstAccount.peekBinding(firstBinding.getBindToken()));
        assertNull(firstAccount.peekBinding(secondBinding.getBindToken()));
        assertSame(secondBinding,
                secondAccount.peekBinding(secondBinding.getBindToken()));

        firstAccount.retire();

        assertTrue(firstBinding.isRetired());
        assertFalse(secondBinding.isRetired());
        assertNull(firstAccount.peekBinding(firstBinding.getBindToken()));
        assertSame(secondBinding,
                secondAccount.peekBinding(secondBinding.getBindToken()));
        assertEquals(1L, secondBinding.nextBindSequence());
    }

    private static IServiceConnection connection() {
        return connection(new FakeBinder());
    }

    private static IServiceConnection connection(IBinder binder) {
        return new IServiceConnection() {
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
        private DeathRecipient deathRecipient;
        private int linkCount;
        private int unlinkCount;

        void die() {
            DeathRecipient recipient = deathRecipient;
            if (recipient != null) {
                recipient.binderDied();
            }
        }

        @Override public String getInterfaceDescriptor() { return "test"; }
        @Override public boolean pingBinder() { return true; }
        @Override public boolean isBinderAlive() { return true; }
        @Override public IInterface queryLocalInterface(String descriptor) { return null; }
        @Override public void dump(FileDescriptor fd, String[] args) { }
        @Override public void dumpAsync(FileDescriptor fd, String[] args) { }
        @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) { return false; }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) {
            deathRecipient = recipient;
            linkCount++;
        }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            if (deathRecipient == recipient) {
                deathRecipient = null;
                unlinkCount++;
                return true;
            }
            return false;
        }
    }
}
