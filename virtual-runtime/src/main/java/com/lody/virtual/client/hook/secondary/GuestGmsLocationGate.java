package com.lody.virtual.client.hook.secondary;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.lody.virtual.client.hook.GuestPermissionPolicy;
import com.lody.virtual.client.hook.proxies.location.LocationAccessPolicy;

/** Gate the requesting guest before a separate microG process can use its own grants. */
public final class GuestGmsLocationGate {
    private GuestGmsLocationGate() {}
    public static boolean isLocation(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        String component = intent.getComponent() == null ? "" : intent.getComponent().getClassName();
        return "com.google.android.location.internal.GoogleLocationManagerService.START".equals(action)
                || "com.google.android.gms.location.reporting.service.START".equals(action)
                || "com.google.android.location.reporting.service.START".equals(action)
                || component.endsWith("LocationManagerService");
    }
    public static void requireLocation() {
        LocationAccessPolicy.requireLocationAccess(null);
        // GMS LocationResult is a separate protocol. Never expose host precision to
        // coarse-only guests until its callback redaction is implemented and verified.
        GuestPermissionPolicy.require("android.permission.ACCESS_FINE_LOCATION");
    }
    public static IBinder wrap(ComponentName component, IBinder original) throws RemoteException {
        if (component == null || original == null || !"com.google.android.gms".equals(component.getPackageName())) return original;
        String descriptor = original.getInterfaceDescriptor();
        if (!"com.google.android.gms.common.internal.IGmsServiceBroker".equals(descriptor)) return original;
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == 46) {
                    int saved = data.dataPosition();
                    try {
                        data.enforceInterface(descriptor);
                        data.readStrongBinder();
                        if (data.readInt() != 0) {
                            int header = data.readInt();
                            int size = (header >>> 16) == 65535 ? data.readInt() : header >>> 16;
                            int end = data.dataPosition() + size;
                            if ((header & 65535) != 20293 || end < data.dataPosition() || end > data.dataSize()) {
                                throw new SecurityException("Invalid GMS service request");
                            }
                            int service = -1;
                            while (data.dataPosition() < end) {
                                int field = data.readInt();
                                int length = (field >>> 16) == 65535 ? data.readInt() : field >>> 16;
                                int start = data.dataPosition();
                                if (length < 0 || start + length > end) throw new SecurityException("Invalid GMS service field");
                                if ((field & 65535) == 2 && length == 4) service = data.readInt();
                                data.setDataPosition(start + length);
                            }
                            if (service == 23 || service == 22) requireLocation();
                        }
                    } finally { data.setDataPosition(saved); }
                } else if (code == 7 || code == 8) {
                    requireLocation(); // legacy getLocationService/getGoogleLocationManagerService
                }
                return original.transact(code, data, reply, flags);
            }
        };
    }
}
