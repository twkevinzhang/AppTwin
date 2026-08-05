package com.lody.virtual.client.hook.proxies.am;

import android.content.Intent;
import android.os.IBinder;
import android.os.IInterface;

import org.junit.Test;

import java.io.FileDescriptor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ServiceCallbackRoutingTest {

    @Test
    public void android17CallbacksUseBindTokenAndImplyRebind() {
        IBinder bindToken = new FakeBinder();
        Object[] args = {new FakeBinder(), bindToken};

        assertSame(bindToken, MethodProxies.serviceBindToken(args[1]));
        assertNull(MethodProxies.legacyServiceIntent(args[1]));
        assertTrue(MethodProxies.serviceDoRebind(args));
    }

    @Test
    public void legacyCallbacksKeepIntentAndExplicitRebindResult() {
        Intent intent = new Intent("test.service");
        Object[] noRebind = {new FakeBinder(), intent, false};
        Object[] rebind = {new FakeBinder(), intent, true};

        assertNull(MethodProxies.serviceBindToken(intent));
        assertSame(intent, MethodProxies.legacyServiceIntent(intent));
        assertFalse(MethodProxies.serviceDoRebind(noRebind));
        assertTrue(MethodProxies.serviceDoRebind(rebind));
    }

    private static final class FakeBinder implements IBinder {
        @Override public String getInterfaceDescriptor() { return "test"; }
        @Override public boolean pingBinder() { return true; }
        @Override public boolean isBinderAlive() { return true; }
        @Override public IInterface queryLocalInterface(String descriptor) { return null; }
        @Override public void dump(FileDescriptor fd, String[] args) { }
        @Override public void dumpAsync(FileDescriptor fd, String[] args) { }
        @Override public boolean transact(int code, android.os.Parcel data,
                                          android.os.Parcel reply, int flags) {
            return false;
        }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) { }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return false;
        }
    }
}
