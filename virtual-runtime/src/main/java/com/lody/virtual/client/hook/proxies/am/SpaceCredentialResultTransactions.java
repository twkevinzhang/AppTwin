package com.lody.virtual.client.hook.proxies.am;

import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Removes only bridge-owned results; the transaction and every other callback still execute. */
final class SpaceCredentialResultTransactions {
    interface Consumer { boolean consume(IBinder token, Object result); }

    private SpaceCredentialResultTransactions() { }

    static void intercept(Object transaction, Consumer consumer) {
        Object callbacks = member(transaction, "getTransactionItems", "mTransactionItems");
        if (!(callbacks instanceof List)) {
            callbacks = member(transaction, "getCallbacks", "mActivityCallbacks");
        }
        if (!(callbacks instanceof List)) return;
        IBinder transactionToken = token(transaction);
        for (Object item : (List<?>) callbacks) {
            if (item == null || !"android.app.servertransaction.ActivityResultItem"
                    .equals(item.getClass().getName())) continue;
            IBinder owner = token(item);
            if (owner == null) owner = transactionToken;
            try {
                Field field = field(item.getClass(), "mResultInfoList");
                if (field == null) continue;
                field.setAccessible(true);
                Object value = field.get(item);
                if (!(value instanceof List)) continue;
                // Install a mutable copy before callbacks, so a reentrant completion cannot
                // expose the owned result through the original framework list.
                List<Object> remaining = new ArrayList<>((List<?>) value);
                field.set(item, remaining);
                filter(remaining, owner, consumer);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // Unknown framework shape remains untouched; never consume a whole transaction.
            }
        }
    }

    static void filter(List<Object> results, IBinder token, Consumer consumer) {
        for (int index = 0; index < results.size();) {
            Object result = results.get(index);
            if (consumer.consume(token, result)) results.remove(index);
            else index++;
        }
    }

    private static IBinder token(Object target) {
        Object value = member(target, "getActivityToken", "mActivityToken");
        return value instanceof IBinder ? (IBinder) value : null;
    }

    private static Object member(Object target, String methodName, String fieldName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            if (value != null) return value;
        } catch (ReflectiveOperationException | SecurityException ignored) { }
        try {
            Field field = field(target.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
