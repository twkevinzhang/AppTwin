package com.lody.virtual.client.hook.proxies.credential;

import android.content.ClipData;
import android.content.Intent;
import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ResultReceiver;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;

/** Framework parcel objects only; no account, activity launch, network, or persistent data. */
@RunWith(AndroidJUnit4.class)
public class SpaceCredentialUiCapabilityInstrumentedTest {
    @Test public void capabilityRemainsReadableWhenSiblingProviderConfigCannotDeserialize() {
        Intent original = new Intent("com.google.android.gms.auth.GOOGLE_SIGN_IN");
        original.putExtra("config", new UnreadableConfig());
        String session = UUID.randomUUID().toString();
        SpaceCredentialUiOwnership.attach(original, 13, session, new ResultReceiver(null) { }, 123456L);
        Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Intent copied = Intent.CREATOR.createFromParcel(parcel);
            Intent capability = SpaceCredentialUiOwnership.capability(copied);
            assertNotNull(capability);
            assertEquals(session, capability.getStringExtra(SpaceCredentialUiOwnership.EXTRA_SESSION_ID));
            assertEquals(13, capability.getIntExtra(SpaceCredentialUiOwnership.EXTRA_USER_ID, -1));
            assertEquals(123456L, capability.getLongExtra(SpaceCredentialUiOwnership.EXTRA_DEADLINE, -1L));
            ResultReceiver received = capability.getParcelableExtra(SpaceCredentialUiOwnership.EXTRA_RECEIVER);
            assertNotNull(received);
            assertEquals(ResultReceiver.class, received.getClass());
            assertEquals(0, copied.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            copied.setExtrasClassLoader(UnreadableConfig.class.getClassLoader());
            try {
                copied.getParcelableExtra("config");
                fail("Fixture config must reject deserialization");
            } catch (BadParcelableException expected) { }
        } finally { parcel.recycle(); }
    }

    @Test public void anonymousReceiverNormalizationPreservesCallbackCapability() throws Exception {
        java.util.concurrent.CountDownLatch callback = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger actual = new java.util.concurrent.atomic.AtomicInteger();
        ResultReceiver original = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, android.os.Bundle data) {
                actual.set(code);
                callback.countDown();
            }
        };
        Intent intent = new Intent();
        SpaceCredentialUiOwnership.attach(intent, 1, UUID.randomUUID().toString(), original);
        ResultReceiver normalized = SpaceCredentialUiOwnership.capability(intent)
                .getParcelableExtra(SpaceCredentialUiOwnership.EXTRA_RECEIVER);
        assertNotNull(normalized);
        assertEquals(ResultReceiver.class, normalized.getClass());
        normalized.send(73, null);
        assertTrue(callback.await(3, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(73, actual.get());
    }

    @Test public void unrelatedClipDataRemainsUnowned() {
        Intent source = new Intent();
        source.setClipData(ClipData.newPlainText("other", "other"));
        assertNull(SpaceCredentialUiOwnership.capability(source));
        try {
            SpaceCredentialUiOwnership.attach(source, 1, UUID.randomUUID().toString(), new ResultReceiver(null));
            fail("Existing unrelated clip must not be replaced");
        } catch (IllegalArgumentException expected) { }
        assertEquals("other", source.getClipData().getItemAt(0).getText().toString());
    }

    public static final class UnreadableConfig implements Parcelable {
        public static final Creator<UnreadableConfig> CREATOR = new Creator<UnreadableConfig>() {
            @Override public UnreadableConfig createFromParcel(Parcel source) {
                throw new BadParcelableException("Fixture-only incompatible provider config");
            }
            @Override public UnreadableConfig[] newArray(int size) { return new UnreadableConfig[size]; }
        };
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel destination, int flags) { destination.writeInt(1); }
    }
}
