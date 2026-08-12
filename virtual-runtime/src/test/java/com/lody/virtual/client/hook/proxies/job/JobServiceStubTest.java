package com.lody.virtual.client.hook.proxies.job;

import android.app.job.JobInfo;
import org.junit.Test;

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
}
