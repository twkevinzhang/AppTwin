package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VirtualUserCleanupCoordinatorTest {

    @Test
    public void deleteThenReuseCannotObserveOldStateAndDoesNotAffectAnotherUser() {
        Map<String, Map<Integer, String>> persisted = persistedState(3, 4);
        VirtualUserCleanupCoordinator coordinator = coordinatorFor(persisted);

        coordinator.clearUserState(3);

        for (Map<Integer, String> service : persisted.values()) {
            assertFalse(service.containsKey(3));
            assertEquals("user-4", service.get(4));
            service.put(3, "new-user-3");
            assertEquals("new-user-3", service.get(3));
        }
    }

    @Test
    public void restartRetryIsIdempotent() {
        Map<String, Map<Integer, String>> durableState = persistedState(8, 9);
        coordinatorFor(durableState).clearUserState(8);

        // Given a service process restart, when cleanup recovery replays the partial user,
        // then the same durable state remains deleted and the other user is untouched.
        coordinatorFor(durableState).clearUserState(8);

        for (Map<Integer, String> service : durableState.values()) {
            assertFalse(service.containsKey(8));
            assertEquals("user-9", service.get(9));
        }
    }

    @Test
    public void failureStillAttemptsEveryStepAndKeepsCleanupFailedClosed() {
        List<String> attempts = new ArrayList<>();
        VirtualUserCleanupCoordinator coordinator = new VirtualUserCleanupCoordinator(Arrays.asList(
                step("accounts", userId -> attempts.add("accounts")),
                step("jobs", userId -> {
                    attempts.add("jobs");
                    throw new IllegalStateException("fixture failure");
                }),
                step("packages", userId -> attempts.add("packages"))
        ));

        try {
            coordinator.clearUserState(6);
            fail("cleanup must fail while any user-scoped state may remain");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("jobs"));
        }

        assertEquals(Arrays.asList("accounts", "jobs", "packages"), attempts);
    }

    private static Map<String, Map<Integer, String>> persistedState(int first, int second) {
        Map<String, Map<Integer, String>> result = new HashMap<>();
        for (String name : Arrays.asList(
                "accounts", "jobs", "notifications", "device", "location", "storage", "packages")) {
            Map<Integer, String> users = new HashMap<>();
            users.put(first, "user-" + first);
            users.put(second, "user-" + second);
            result.put(name, users);
        }
        return result;
    }

    private static VirtualUserCleanupCoordinator coordinatorFor(
            Map<String, Map<Integer, String>> persisted) {
        List<VirtualUserCleanupCoordinator.CleanupStep> steps = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, String>> entry : persisted.entrySet()) {
            steps.add(step(entry.getKey(), userId -> entry.getValue().remove(userId)));
        }
        return new VirtualUserCleanupCoordinator(steps);
    }

    private interface Action {
        void run(int userId);
    }

    private static VirtualUserCleanupCoordinator.CleanupStep step(
            String name, Action action) {
        return new VirtualUserCleanupCoordinator.CleanupStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void clear(int userId) {
                action.run(userId);
            }
        };
    }
}
