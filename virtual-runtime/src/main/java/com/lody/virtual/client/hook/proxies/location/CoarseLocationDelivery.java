package com.lody.virtual.client.hook.proxies.location;

import android.location.Location;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.reflect.Proxy;
import java.util.List;

/** Redacts host precision before delivering a coarse-only guest callback. */
final class CoarseLocationDelivery {
    private static final java.util.Map<IBinder, IInterface> wrappers = new java.util.HashMap<>();
    private CoarseLocationDelivery() {}
    static synchronized void unregister(Object[] args) {
        for (int i = 0; args != null && i < args.length; i++) {
            if (!(args[i] instanceof IInterface)) continue;
            IInterface replacement = wrappers.remove(((IInterface) args[i]).asBinder());
            if (replacement != null) args[i] = replacement;
        }
    }
    static Location redact(Location source) {
        if (source == null) return null;
        Location result = new Location(source);
        result.setLatitude(Math.round(source.getLatitude() * 100D) / 100D);
        result.setLongitude(Math.round(source.getLongitude() * 100D) / 100D);
        result.setAccuracy(Math.max(1500F, source.getAccuracy()));
        result.removeAltitude(); result.removeBearing(); result.removeSpeed();
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            result.removeVerticalAccuracy(); result.removeSpeedAccuracy(); result.removeBearingAccuracy();
        }
        result.setExtras(null);
        return result;
    }
    static synchronized boolean wrapCallbacks(Object[] args) {
        boolean wrapped = false;
        for (int i = 0; args != null && i < args.length; i++) {
            if (args[i] instanceof android.app.PendingIntent) {
                throw new SecurityException("Coarse PendingIntent location is not supported");
            }
            if (!(args[i] instanceof IInterface)) continue;
            IInterface listener = (IInterface) args[i];
            IBinder original = listener.asBinder();
            IInterface existing = wrappers.get(original);
            if (existing != null) { args[i] = existing; wrapped = true; continue; }
            final String descriptor;
            try { descriptor = original.getInterfaceDescriptor(); }
            catch (RemoteException failure) { throw new SecurityException("Location listener unavailable", failure); }
            if (!"android.location.ILocationListener".equals(descriptor)
                    && !"android.location.ILocationCallback".equals(descriptor)) continue;
            if (wrappers.size() >= 256) throw new SecurityException("Location listener quota exceeded");
            Binder filtered = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    if (code != 1) return original.transact(code, data, reply, flags);
                    data.enforceInterface(descriptor);
                    Parcel output = Parcel.obtain();
                    try {
                        output.writeInterfaceToken(descriptor);
                        if (android.os.Build.VERSION.SDK_INT >= 31 && descriptor.endsWith("ILocationListener")) {
                            List<Location> locations = data.createTypedArrayList(Location.CREATOR);
                            if (locations != null) for (int j = 0; j < locations.size(); j++) {
                                locations.set(j, redact(locations.get(j)));
                            }
                            output.writeTypedList(locations);
                        } else {
                            Location location = data.readInt() != 0 ? Location.CREATOR.createFromParcel(data) : null;
                            output.writeInt(location == null ? 0 : 1);
                            if (location != null) redact(location).writeToParcel(output, 0);
                        }
                        output.appendFrom(data, data.dataPosition(), data.dataAvail());
                        output.setDataPosition(0);
                        return original.transact(code, output, reply, flags);
                    } finally { output.recycle(); }
                }
            };
            Class<?> callbackInterface;
            try { callbackInterface = Class.forName(descriptor); }
            catch (ClassNotFoundException unavailable) { throw new SecurityException("Unknown location callback", unavailable); }
            args[i] = Proxy.newProxyInstance(callbackInterface.getClassLoader(),
                    new Class<?>[]{callbackInterface}, (proxy, method, values) -> {
                        if ("asBinder".equals(method.getName())) return filtered;
                        return method.invoke(listener, values);
                    });
            wrappers.put(original, (IInterface) args[i]);
            wrapped = true;
        }
        return wrapped;
    }
}
