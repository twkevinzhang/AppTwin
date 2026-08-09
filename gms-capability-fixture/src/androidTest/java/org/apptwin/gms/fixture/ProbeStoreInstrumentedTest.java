package org.apptwin.gms.fixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProbeStoreInstrumentedTest {
    @Test
    public void sentinelAndProbeEvidenceSurviveStoreRecreation() {
        Context context = ApplicationProvider.getApplicationContext();
        ProbeStore first = new ProbeStore(context);
        String sentinel = first.getOrCreateSentinel();
        first.save(new ProbeResult(
                ProbeId.PLAY_BILLING,
                ProbeStatus.UNSUPPORTED,
                "unsupported:test-fixture",
                123));

        ProbeStore restarted = new ProbeStore(context);

        assertNotNull(sentinel);
        assertEquals(sentinel, restarted.getOrCreateSentinel());
        assertEquals(
                ProbeStatus.UNSUPPORTED,
                restarted.readAll().get(ProbeId.PLAY_BILLING).status);
    }
}
