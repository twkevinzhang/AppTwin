package com.lody.virtual.client.hook.proxies.shortcut;

import com.android.internal.infra.AndroidFuture;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.*;

public class ShortcutFutureResultTest {
    @Test
    public void delayedResultRetainsAndroidFutureAndFiltersOnlyAfterCompletion() throws Exception {
        AndroidFuture<List<String>> original = new AndroidFuture<>();
        int[] repairs = {0};
        AndroidFuture<?> transformed = (AndroidFuture<?>) ShortcutFutureResult.transform(
                original, AndroidFuture.class,
                payload -> ShortcutServiceStub.filterAndTransformGuestShortcuts(
                        (List<String>) payload, "host"::equals, shortcut -> repairs[0]++));
        assertFalse(transformed.isDone());
        assertEquals(0, repairs[0]);
        original.complete(Arrays.asList("host", "guest"));
        assertEquals(Arrays.asList("guest"), transformed.join());
        assertEquals(1, repairs[0]);
        assertEquals(Arrays.asList("host", "guest"), original.join());
    }

    @Test
    public void alreadyCompletedResultAndNullPayloadAreHandled() throws Exception {
        AndroidFuture<Object> original = new AndroidFuture<>();
        original.complete(null);
        AndroidFuture<?> transformed = (AndroidFuture<?>) ShortcutFutureResult.transform(
                original, AndroidFuture.class, payload -> {
                    assertNull(payload);
                    return null;
                });
        assertTrue(transformed.isDone());
        assertNull(transformed.join());
    }

    @Test
    public void sourceFailurePreservesCauseAndDoesNotTransform() throws Exception {
        AndroidFuture<Object> original = new AndroidFuture<>();
        AndroidFuture<?> transformed = (AndroidFuture<?>) ShortcutFutureResult.transform(
                original, AndroidFuture.class, payload -> { throw new AssertionError(); });
        IllegalStateException failure = new IllegalStateException("upstream");
        original.completeExceptionally(failure);
        assertFutureFailure(transformed, failure);
    }

    @Test
    public void sourceCancellationIsCancellationRatherThanSuccessfulEmptyResult() throws Exception {
        AndroidFuture<Object> original = new AndroidFuture<>();
        AndroidFuture<?> transformed = (AndroidFuture<?>) ShortcutFutureResult.transform(
                original, AndroidFuture.class, payload -> { throw new AssertionError(); });
        original.cancel(false);
        assertTrue(transformed.isCancelled());
    }

    @Test
    public void repairFailureCompletesReturnedFutureExceptionally() throws Exception {
        AndroidFuture<Object> original = new AndroidFuture<>();
        IllegalArgumentException failure = new IllegalArgumentException("unsupported payload");
        AndroidFuture<?> transformed = (AndroidFuture<?>) ShortcutFutureResult.transform(
                original, AndroidFuture.class, payload -> { throw failure; });
        original.complete(new Object());
        assertFutureFailure(transformed, failure);
    }

    @Test
    public void plainFutureAndSynchronousListAreNotAndroidFutureResults() {
        assertFalse(ShortcutFutureResult.isAndroidFuture(new CompletableFuture<>(),
                CompletableFuture.class));
        assertFalse(ShortcutFutureResult.isAndroidFuture(Arrays.asList("guest"), List.class));
        assertFalse(ShortcutFutureResult.isAndroidFuture(new AndroidFuture<>(), List.class));
        assertTrue(ShortcutFutureResult.isAndroidFuture(new AndroidFuture<>(), AndroidFuture.class));
    }

    @Test
    public void synchronousListRepairRetainsListPayloadWithoutFutureWrapper() throws Exception {
        java.lang.reflect.Method repair = ShortcutServiceStub.class.getDeclaredMethod(
                "repairShortcutListResult", Object.class, String.class,
                java.lang.reflect.Method.class);
        repair.setAccessible(true);
        java.lang.reflect.Method contract = ShortcutFutureResultTest.class.getDeclaredMethod(
                "synchronousShortcuts");
        Object result = repair.invoke(null, Arrays.asList("guest"), "host", contract);
        assertTrue(result instanceof List);
        assertEquals(Arrays.asList("guest"), result);
        assertNull(repair.invoke(null, null, "host", contract));
    }

    public static List<String> synchronousShortcuts() {
        return null;
    }

    private static void assertFutureFailure(CompletableFuture<?> future, Throwable expected) {
        try {
            future.join();
            fail("Expected exceptional completion");
        } catch (CompletionException error) {
            assertSame(expected, error.getCause());
        }
    }
}
