package org.apptwin.gms.fixture;

import java.util.Objects;

public final class NotificationRoute {
    public final String targetClassName;
    public final String groupSentinel;

    public NotificationRoute(String targetClassName, String groupSentinel) {
        this.targetClassName = Objects.requireNonNull(targetClassName);
        this.groupSentinel = Objects.requireNonNull(groupSentinel);
    }
}
