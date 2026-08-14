package com.lody.virtual.client.stub;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccountHandle;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.hook.proxies.telecom.TelecomPhoneAccountPolicy;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;

/**
 * Host-owned Telecom endpoint which invokes LINE's ConnectionService inside its already-bound
 * virtual process. Each nested class is pinned to the matching virtual process slot.
 */
public class StubConnectionService extends ConnectionService {
    private static final String TAG = StubConnectionService.class.getSimpleName();

    private ConnectionService guestService;
    private String guestServiceKey;
    private LineIncomingCallRingtone incomingCallRingtone;

    @Override
    public void onCreate() {
        super.onCreate();
        incomingCallRingtone = new LineIncomingCallRingtone(this);
    }

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        GuestCall call = prepareGuestCall(connectionManagerPhoneAccount, request);
        if (call == null) {
            return null;
        }
        try {
            Connection connection = call.service.onCreateIncomingConnection(
                    call.originalHandle, call.request);
            if (connection != null && incomingCallRingtone != null) {
                incomingCallRingtone.start(
                        connection, VClientImpl.get().getCurrentApplication(), call.packageName);
            }
            return connection;
        } catch (Throwable error) {
            VLog.e(TAG, "Guest incoming Connection creation failed", error);
            return null;
        }
    }

    @Override
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        stopIncomingCallRingtone();
        GuestCall call = prepareGuestCall(connectionManagerPhoneAccount, request);
        if (call == null) {
            return null;
        }
        try {
            return call.service.onCreateOutgoingConnection(call.originalHandle, call.request);
        } catch (Throwable error) {
            VLog.e(TAG, "Guest outgoing Connection creation failed", error);
            return null;
        }
    }

    @Override
    public void onCreateIncomingConnectionFailed(
            PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        stopIncomingCallRingtone();
        GuestCall call = prepareGuestCall(connectionManagerPhoneAccount, request);
        if (call != null) {
            try {
                call.service.onCreateIncomingConnectionFailed(call.originalHandle, call.request);
            } catch (Throwable error) {
                VLog.e(TAG, "Guest incoming Connection failure callback failed", error);
            }
        }
    }

    @Override
    public void onCreateOutgoingConnectionFailed(
            PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        stopIncomingCallRingtone();
        GuestCall call = prepareGuestCall(connectionManagerPhoneAccount, request);
        if (call != null) {
            try {
                call.service.onCreateOutgoingConnectionFailed(call.originalHandle, call.request);
            } catch (Throwable error) {
                VLog.e(TAG, "Guest outgoing Connection failure callback failed", error);
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopIncomingCallRingtone();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopIncomingCallRingtone();
        super.onDestroy();
    }

    private void stopIncomingCallRingtone() {
        if (incomingCallRingtone != null) {
            incomingCallRingtone.stop();
        }
    }

    private GuestCall prepareGuestCall(PhoneAccountHandle hostHandle, ConnectionRequest request) {
        TelecomPhoneAccountPolicy.Identity identity = hostHandle == null
                ? null : TelecomPhoneAccountPolicy.decode(hostHandle.getId());
        String currentPackage = VClientImpl.get().getCurrentPackage();
        int currentUserId = VUserHandle.getUserId(VClientImpl.get().getVUid());
        if (!VClientImpl.get().isBound()
                || !TelecomPhoneAccountPolicy.canRoute(
                        identity, currentPackage, currentUserId)) {
            VLog.w(TAG, "Rejecting Telecom request outside the bound LINE guest");
            return null;
        }
        try {
            VLog.i(TAG, "Routing Telecom connection to cloned LINE user="
                    + identity.virtualUserId);
            ConnectionService service = getGuestService(identity);
            PhoneAccountHandle originalHandle = new PhoneAccountHandle(
                    new android.content.ComponentName(identity.packageName, identity.className),
                    identity.originalId, android.os.Process.myUserHandle());
            ConnectionRequest originalRequest = restoreRequest(
                    request, originalHandle, VClientImpl.get().getCurrentApplication().getClassLoader());
            return new GuestCall(service, originalHandle, originalRequest, identity.packageName);
        } catch (Throwable error) {
            VLog.e(TAG, "Unable to load guest Telecom service", error);
            return null;
        }
    }

    private synchronized ConnectionService getGuestService(
            TelecomPhoneAccountPolicy.Identity identity) throws Exception {
        String key = identity.packageName + "/" + identity.className;
        if (guestService != null && key.equals(guestServiceKey)) {
            return guestService;
        }
        ClassLoader classLoader = VClientImpl.get().getCurrentApplication().getClassLoader();
        Class<?> serviceClass = classLoader.loadClass(identity.className);
        Object instance = serviceClass.getDeclaredConstructor().newInstance();
        if (!(instance instanceof ConnectionService)) {
            throw new IllegalArgumentException("Guest component is not a ConnectionService");
        }
        guestService = (ConnectionService) instance;
        guestServiceKey = key;
        return guestService;
    }

    private static ConnectionRequest restoreRequest(
            ConnectionRequest request, PhoneAccountHandle originalHandle, ClassLoader classLoader) {
        if (request == null) {
            return null;
        }
        Bundle extras = request.getExtras();
        if (extras != null) {
            extras.setClassLoader(classLoader);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new ConnectionRequest(
                    originalHandle, request.getAddress(), extras, request.getVideoState());
        }
        return new ConnectionRequest(originalHandle, request.getAddress(), extras);
    }

    private static final class GuestCall {
        final ConnectionService service;
        final PhoneAccountHandle originalHandle;
        final ConnectionRequest request;
        final String packageName;

        GuestCall(ConnectionService service, PhoneAccountHandle originalHandle,
                  ConnectionRequest request, String packageName) {
            this.service = service;
            this.originalHandle = originalHandle;
            this.request = request;
            this.packageName = packageName;
        }
    }

    public static class C0 extends StubConnectionService {}
    public static class C1 extends StubConnectionService {}
    public static class C2 extends StubConnectionService {}
    public static class C3 extends StubConnectionService {}
    public static class C4 extends StubConnectionService {}
    public static class C5 extends StubConnectionService {}
    public static class C6 extends StubConnectionService {}
    public static class C7 extends StubConnectionService {}
    public static class C8 extends StubConnectionService {}
    public static class C9 extends StubConnectionService {}
    public static class C10 extends StubConnectionService {}
    public static class C11 extends StubConnectionService {}
    public static class C12 extends StubConnectionService {}
    public static class C13 extends StubConnectionService {}
    public static class C14 extends StubConnectionService {}
    public static class C15 extends StubConnectionService {}
    public static class C16 extends StubConnectionService {}
    public static class C17 extends StubConnectionService {}
    public static class C18 extends StubConnectionService {}
    public static class C19 extends StubConnectionService {}
    public static class C20 extends StubConnectionService {}
    public static class C21 extends StubConnectionService {}
    public static class C22 extends StubConnectionService {}
    public static class C23 extends StubConnectionService {}
    public static class C24 extends StubConnectionService {}
    public static class C25 extends StubConnectionService {}
    public static class C26 extends StubConnectionService {}
    public static class C27 extends StubConnectionService {}
    public static class C28 extends StubConnectionService {}
    public static class C29 extends StubConnectionService {}
    public static class C30 extends StubConnectionService {}
    public static class C31 extends StubConnectionService {}
    public static class C32 extends StubConnectionService {}
    public static class C33 extends StubConnectionService {}
    public static class C34 extends StubConnectionService {}
    public static class C35 extends StubConnectionService {}
    public static class C36 extends StubConnectionService {}
    public static class C37 extends StubConnectionService {}
    public static class C38 extends StubConnectionService {}
    public static class C39 extends StubConnectionService {}
    public static class C40 extends StubConnectionService {}
    public static class C41 extends StubConnectionService {}
    public static class C42 extends StubConnectionService {}
    public static class C43 extends StubConnectionService {}
    public static class C44 extends StubConnectionService {}
    public static class C45 extends StubConnectionService {}
    public static class C46 extends StubConnectionService {}
    public static class C47 extends StubConnectionService {}
    public static class C48 extends StubConnectionService {}
    public static class C49 extends StubConnectionService {}
}
