package org.apptwin.gms.fixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class TokenIsolationRecordTest {
    @Test
    public void samePackageAndSenderRemainDistinctAcrossGroupSentinels() {
        TokenIsolationRecord first = new TokenIsolationRecord(
                "org.apptwin.gms.fixture", "group-a", "fixture-sender", "not-observed");
        TokenIsolationRecord second = new TokenIsolationRecord(
                "org.apptwin.gms.fixture", "group-b", "fixture-sender", "not-observed");

        assertNotEquals(first.isolationKey(), second.isolationKey());
        assertEquals("org.apptwin.gms.fixture|group-a|fixture-sender", first.isolationKey());
    }

    @Test
    public void notificationRouteCarriesOnlyTargetAndGroupSentinel() {
        NotificationRoute route = new NotificationRoute(
                MainActivity.class.getName(), "group-a");

        assertEquals(MainActivity.class.getName(), route.targetClassName);
        assertEquals("group-a", route.groupSentinel);
    }
}
