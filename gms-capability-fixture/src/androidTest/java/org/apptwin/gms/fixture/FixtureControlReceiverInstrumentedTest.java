package org.apptwin.gms.fixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class FixtureControlReceiverInstrumentedTest {
    @Test
    public void exportedControlIsSignatureProtectedAndReturnsPersistedSentinel() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ComponentName receiverName = new ComponentName(context, FixtureControlReceiver.class);
        ActivityInfo receiver = context.getPackageManager().getReceiverInfo(
                receiverName, PackageManager.GET_META_DATA);
        assertEquals(
                "org.apptwin.gms.fixture.permission.TEST_CONTROL",
                receiver.permission);

        Bundle result = sendOrdered(context, receiverName, FixtureControlReceiver.ACTION_READ);

        assertNotNull(result);
        assertEquals(
                new ProbeStore(context).getOrCreateSentinel(),
                result.getString("sentinel"));
    }

    @Test
    public void runControlReturnsAllLocalEvidenceWithoutPromotingExternalCapabilities()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ComponentName receiverName = new ComponentName(context, FixtureControlReceiver.class);

        Bundle result = sendOrdered(context, receiverName, FixtureControlReceiver.ACTION_RUN);

        assertEquals(ProbeId.values().length, result.getStringArrayList("probe_ids").size());
        assertEquals(
                ProbeStatus.UNSUPPORTED.name(),
                result.getBundle("probe.PLAY_BILLING").getString("status"));
        assertEquals(
                ProbeStatus.UNSUPPORTED.name(),
                result.getBundle("probe.PLAY_INTEGRITY").getString("status"));
        assertEquals(
                ProbeStatus.EXTERNAL_UNTESTED.name(),
                result.getBundle("probe.FCM_LOCAL_CONTRACT").getString("status"));
        assertEquals(
                ProbeStatus.EXTERNAL_UNTESTED.name(),
                result.getBundle("probe.MAPS_RENDERER").getString("status"));
        assertEquals(
                ProbeStatus.EXTERNAL_UNTESTED.name(),
                result.getBundle("probe.ACCOUNT_SIGN_IN").getString("status"));
        assertEquals(
                ProbeStatus.EXTERNAL_UNTESTED.name(),
                result.getBundle("probe.CAST").getString("status"));
        assertEquals(
                ProbeStatus.EXTERNAL_UNTESTED.name(),
                result.getBundle("probe.NEARBY").getString("status"));
        assertEquals(
                ProbeStatus.PERMISSION_DENIED.name(),
                result.getBundle("probe.FUSED_LOCATION").getString("status"));
    }

    private static Bundle sendOrdered(Context context, ComponentName receiverName, String action)
            throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicReference<Integer> resultCode = new AtomicReference<>();
        Intent request = new Intent(action).setComponent(receiverName);
        context.sendOrderedBroadcast(
                request,
                null,
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ignored, Intent response) {
                        resultCode.set(getResultCode());
                        result.set(getResultExtras(false));
                        complete.countDown();
                    }
                },
                null,
                Activity.RESULT_CANCELED,
                null,
                null);

        assertEquals(true, complete.await(10, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(Activity.RESULT_OK), resultCode.get());
        return result.get();
    }
}
