package com.lody.virtual.server.pm;

import android.os.Build;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.server.accounts.VAccountManagerService;
import com.lody.virtual.server.device.VDeviceManagerService;
import com.lody.virtual.server.job.VJobSchedulerService;
import com.lody.virtual.server.location.VirtualLocationService;
import com.lody.virtual.server.notification.VNotificationManagerService;
import com.lody.virtual.server.vs.VirtualStorageService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Coordinates durable, idempotent cleanup of state keyed by a virtual user id.
 *
 * <p>Every step is attempted. If any step fails, the caller must keep the user marked partial and
 * retry on the next startup; removing the user record after an incomplete cleanup could expose old
 * state when the numeric id is reused.</p>
 */
final class VirtualUserCleanupCoordinator {
    interface CleanupStep {
        String name();

        void clear(int userId) throws Exception;
    }

    private final List<CleanupStep> steps;

    VirtualUserCleanupCoordinator(List<CleanupStep> steps) {
        this.steps = new ArrayList<>(steps);
    }

    static VirtualUserCleanupCoordinator create(VPackageManagerService packageManager) {
        return new VirtualUserCleanupCoordinator(Arrays.asList(
                step("accounts", userId -> {
                    VAccountManagerService service = VAccountManagerService.get();
                    if (service != null) {
                        service.clearUserState(userId);
                    }
                }),
                step("jobs", userId -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        VJobSchedulerService.get().clearUserState(userId);
                    }
                }),
                step("notifications", userId -> {
                    VNotificationManagerService.getOrCreate(VirtualCore.get().getContext())
                            .clearUserState(userId);
                }),
                step("runtime-ownership", userId -> {
                    if (!com.lody.virtual.server.am.VActivityManagerService.get()
                            .clearUserRuntimeState(userId)) {
                        throw new IllegalStateException("runtime ownership remains");
                    }
                }),
                step("device", userId -> VDeviceManagerService.get().clearUserState(userId)),
                step("location", userId -> VirtualLocationService.get().clearUserState(userId)),
                step("virtual-storage", userId -> VirtualStorageService.get().clearUserState(userId)),
                step("packages", packageManager::cleanUpUser)
        ));
    }

    void clearUserState(int userId) {
        List<String> failures = new ArrayList<>();
        for (CleanupStep step : steps) {
            try {
                step.clear(userId);
            } catch (Exception failure) {
                // Never log account names, tokens, paths, or service exception messages here.
                failures.add(step.name());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Incomplete virtual-user cleanup: " + failures);
        }
    }

    private interface Action {
        void clear(int userId) throws Exception;
    }

    private static CleanupStep step(final String name, final Action action) {
        return new CleanupStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void clear(int userId) throws Exception {
                action.clear(userId);
            }
        };
    }
}
