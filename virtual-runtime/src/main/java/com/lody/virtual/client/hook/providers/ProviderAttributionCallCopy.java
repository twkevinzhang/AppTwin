package com.lody.virtual.client.hook.providers;

import com.lody.virtual.helper.utils.Reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Translates only a caller-owned root on a private copy, never a Context's shared source. */
final class ProviderAttributionCallCopy {
    interface Copier {
        Object copy(Object source);
    }

    private ProviderAttributionCallCopy() { }

    static Object copyForCall(Object source, String guestPackage, String hostPackage,
            int guestUid, int hostUid, String targetPackage) {
        return copyForCall(source, guestPackage, hostPackage, guestUid, hostUid,
                targetPackage, original -> {
                    Object state = Reflect.on(original).get("mAttributionSourceState");
                    return Reflect.on(original.getClass()).create(copyRuntimeState(state)).get();
                });
    }

    static Object copyForCall(Object source, String guestPackage, String hostPackage,
            int guestUid, int hostUid, String targetPackage, Copier copier) {
        if (source == null || targetPackage == null || targetPackage.length() == 0) {
            return source;
        }
        if (!targetPackage.equals(hostPackage) && !targetPackage.equals(guestPackage)) {
            throw new IllegalArgumentException("Attribution target must be host or current guest");
        }
        Object originalState = Reflect.on(source).get("mAttributionSourceState");
        int uid = Reflect.on(originalState).get("uid");
        String pkg = Reflect.on(originalState).get("packageName");
        boolean ownPackage = hostPackage != null && hostPackage.equals(pkg)
                || guestPackage != null && guestPackage.equals(pkg);
        if (!ownPackage || (uid != hostUid && uid != guestUid)) {
            // Do not forge another principal's identity or rewrite a delegated chain.
            return source;
        }
        if (uid == hostUid && targetPackage.equals(pkg)) {
            return source;
        }
        Object copy = copier.copy(source);
        Object copyState = Reflect.on(copy).get("mAttributionSourceState");
        if (copy == source || copyState == originalState) {
            throw new IllegalStateException("AttributionSource copy aliases caller state");
        }
        // The runtime state copy preserves pid, token, tag, permissions, device and next.
        // Only this new root is writable; next remains untouched even when shared.
        Reflect.on(copyState).set("uid", hostUid);
        Reflect.on(copyState).set("packageName", targetPackage);
        return copy;
    }

    static Object copyRuntimeState(Object state) {
        try {
            Class<?> type = state.getClass();
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object copy = constructor.newInstance();
            for (Class<?> current = type; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        field.set(copy, field.get(state));
                    }
                }
            }
            return copy;
        } catch (ReflectiveOperationException error) {
            // Never fall back to modifying the caller's source on a changed framework schema.
            throw new IllegalStateException("Cannot copy attribution runtime state", error);
        }
    }
}
