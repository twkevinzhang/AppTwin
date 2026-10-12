package com.lody.virtual.client;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;

/** Read-only adapter for the reviewed microG v0.3.15.250932 McsService fields. */
final class TrustedMcsTcpProbe {
    static long[] read(ClassLoader loader) {
        try {
            Class<?> service = Class.forName("org.microg.gms.gcm.McsService", false, loader);
            Socket socket = (Socket) readField(service, "sslSocket");
            long sessionStart = (Long) readField(service, "startTimestamp");
            if (socket == null || socket.isClosed()) return new long[]{2, sessionStart, 0, 0};
            Method descriptorMethod = socket.getClass().getMethod("getFileDescriptor$");
            descriptorMethod.setAccessible(true);
            FileDescriptor descriptor = (FileDescriptor) descriptorMethod.invoke(socket);
            int tcp = NativeEngine.nativeTcpState(descriptor);
            Object input = readField(service, "inputStream");
            Object output = readField(service, "outputStream");
            long ping = (Long) readField(service, "lastHeartbeatPingElapsedRealtime");
            long ack = (Long) readField(service, "lastHeartbeatAckElapsedRealtime");
            boolean streams = input instanceof Thread && ((Thread) input).isAlive()
                    && output instanceof Thread && ((Thread) output).isAlive();
            boolean heartbeat = ack >= ping || android.os.SystemClock.elapsedRealtime() - ping <= 90_000L;
            // A concurrently replaced socket cannot certify the returned session.
            if (readField(service, "sslSocket") != socket || socket.isClosed()) return new long[]{0, 0, 0, 0};
            return new long[]{tcp, sessionStart, streams && heartbeat ? 1 : 0,
                    Integer.toUnsignedLong(System.identityHashCode(socket))};
        } catch (Throwable inaccessible) {
            return new long[]{0, 0, 0, 0};
        }
    }

    private static Object readField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
