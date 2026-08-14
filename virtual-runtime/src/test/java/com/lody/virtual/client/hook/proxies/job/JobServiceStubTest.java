package com.lody.virtual.client.hook.proxies.job;

import android.app.job.JobInfo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class JobServiceStubTest {

    @Test
    public void android15NamespacePrefixIsNotAssumedToBeJobInfo() {
        assertNull(JobServiceStub.findJobInfo(new Object[]{null, "namespace"}));
    }

    @Test
    public void missingJobInfoFailsClosed() {
        assertNull(JobServiceStub.findJobInfo(new Object[]{null, "namespace"}));
    }

    @Test
    public void android15NullableNamespaceDoesNotHideJobId() {
        assertEquals(Integer.valueOf(42),
                JobServiceStub.findJobId(new Object[]{null, 42}));
    }

    @Test
    public void legacyJobIdRemainsSupported() {
        assertEquals(Integer.valueOf(7), JobServiceStub.findJobId(new Object[]{7}));
    }

    @Test
    public void missingJobIdFailsClosed() {
        assertNull(JobServiceStub.findJobId(new Object[]{null, "namespace"}));
    }
}
