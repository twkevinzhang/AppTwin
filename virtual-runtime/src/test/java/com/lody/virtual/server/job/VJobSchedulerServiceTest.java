package com.lody.virtual.server.job;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VJobSchedulerServiceTest {

    @Test
    public void cancelAllRemovesEveryJobForCallerInsteadOfOnlyFirst() {
        int callerVuid = 310_001;
        Map<VJobSchedulerService.JobId, VJobSchedulerService.JobConfig> jobs = new HashMap<>();
        put(jobs, callerVuid, "com.example.a", 1, 101);
        put(jobs, callerVuid, "com.example.a", 2, 102);
        put(jobs, 410_001, "com.example.a", 1, 201);
        List<Integer> cancelled = new ArrayList<>();

        boolean changed = VJobSchedulerService.removeMatchingJobs(
                jobs, job -> job.vuid == callerVuid, cancelled::add);

        assertTrue(changed);
        assertEquals(2, cancelled.size());
        assertTrue(cancelled.contains(101));
        assertTrue(cancelled.contains(102));
        assertEquals(1, jobs.size());
        assertEquals(410_001, jobs.keySet().iterator().next().vuid);
    }

    @Test
    public void clearingMissingUserIsIdempotent() {
        Map<VJobSchedulerService.JobId, VJobSchedulerService.JobConfig> jobs = new HashMap<>();
        put(jobs, 410_001, "com.example.b", 1, 201);

        boolean changed = VJobSchedulerService.removeMatchingJobs(
                jobs, job -> job.vuid / 100_000 == 3, ignored -> { });

        assertFalse(changed);
        assertEquals(1, jobs.size());
    }

    @Test
    public void packageSuspensionRemovesOnlyMatchingPackageAndUserJobs() {
        int userAApp = 310_001;
        int userBOtherApp = 410_001;
        Map<VJobSchedulerService.JobId, VJobSchedulerService.JobConfig> jobs = new HashMap<>();
        put(jobs, userAApp, "com.google.android.gms", 1, 101);
        put(jobs, userAApp, "com.example.other", 2, 102);
        put(jobs, userBOtherApp, "com.google.android.gms", 3, 103);
        List<Integer> cancelled = new ArrayList<>();

        boolean changed = VJobSchedulerService.removeMatchingJobs(
                jobs,
                job -> job.vuid / 100_000 == 3
                        && "com.google.android.gms".equals(job.packageName),
                cancelled::add);

        assertTrue(changed);
        assertEquals(java.util.Collections.singletonList(101), cancelled);
        assertEquals(2, jobs.size());
        assertTrue(jobs.keySet().stream().anyMatch(
                job -> "com.example.other".equals(job.packageName)));
        assertTrue(jobs.keySet().stream().anyMatch(job -> job.vuid == userBOtherApp));
    }

    private static void put(
            Map<VJobSchedulerService.JobId, VJobSchedulerService.JobConfig> jobs,
            int vuid, String packageName, int clientJobId, int virtualJobId) {
        jobs.put(
                new VJobSchedulerService.JobId(vuid, packageName, clientJobId),
                new VJobSchedulerService.JobConfig(virtualJobId, "JobService", null));
    }
}
