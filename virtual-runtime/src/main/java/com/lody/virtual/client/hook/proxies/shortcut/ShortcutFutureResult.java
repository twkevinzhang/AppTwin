package com.lody.virtual.client.hook.proxies.shortcut;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Isolated from the shortcut proxy so pre-Android-12 devices need not load future APIs. */
final class ShortcutFutureResult {
    private static final String ANDROID_FUTURE = "com.android.internal.infra.AndroidFuture";

    private ShortcutFutureResult() {}

    static boolean isAndroidFuture(Object result, Class<?> returnType) {
        if (result == null || !returnType.isInstance(result)) {
            return false;
        }
        for (Class<?> type = result.getClass(); type != null; type = type.getSuperclass()) {
            if (ANDROID_FUTURE.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    static Object transform(Object source, Class<?> returnType,
                            Function<Object, Object> transformer) throws ReflectiveOperationException {
        if (!isAndroidFuture(source, returnType) || !(source instanceof CompletableFuture)) {
            throw new IllegalArgumentException("Invalid AndroidFuture shortcut result");
        }
        // AndroidFuture has a public no-argument constructor. A plain CompletableFuture
        // from thenApply would violate the Binder method's declared return contract.
        CompletableFuture<Object> target = (CompletableFuture<Object>)
                returnType.getConstructor().newInstance();
        CompletableFuture<?> original = (CompletableFuture<?>) source;
        original.whenComplete((payload, error) -> {
            if (original.isCancelled()) {
                target.cancel(false);
            } else if (error != null) {
                target.completeExceptionally(error);
            } else {
                try {
                    target.complete(transformer.apply(payload));
                } catch (Throwable transformError) {
                    target.completeExceptionally(transformError);
                }
            }
        });
        return target;
    }
}
