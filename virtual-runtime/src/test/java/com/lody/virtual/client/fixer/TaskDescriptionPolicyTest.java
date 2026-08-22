package com.lody.virtual.client.fixer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TaskDescriptionPolicyTest {

    @Test
    public void recentTaskLabelIsAlwaysAppTwin() {
        assertEquals("AppTwin", TaskDescriptionPolicy.RECENT_TASK_LABEL);
    }
}
