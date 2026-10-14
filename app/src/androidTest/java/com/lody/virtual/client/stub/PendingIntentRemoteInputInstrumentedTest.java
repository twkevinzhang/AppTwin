package com.lody.virtual.client.stub;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.RemoteInput;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;

/** Local framework fixtures only; no messages, account access or persisted data. */
@RunWith(AndroidJUnit4.class)
public class PendingIntentRemoteInputInstrumentedTest {
    private final RemoteInput input = new RemoteInput.Builder("reply-fixture").build();

    @Test public void transfersTextWithoutChangingRoutingOrOriginal() {
        Intent original = original();
        Intent wrapper = new Intent().putExtra("_VA_|_user_id_", 999)
                .putExtra("untrusted", "outer");
        Bundle text = new Bundle();
        text.putCharSequence(input.getResultKey(), "fixture reply");
        RemoteInput.addResultsToIntent(new RemoteInput[]{input}, wrapper, text);
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, original, "receiver");
        assertNotNull(result);
        assertEquals("fixture reply", RemoteInput.getResultsFromIntent(result)
                .getCharSequence(input.getResultKey()).toString());
        assertRouting(original, result);
        assertFalse(result.hasExtra("untrusted"));
        assertNull(RemoteInput.getResultsFromIntent(original));
    }

    @Test public void transfersDataAndSourceUsingFrameworkSchemaOnly() {
        assumeTrue(Build.VERSION.SDK_INT >= 28);
        Intent wrapper = new Intent();
        Uri uri = Uri.parse("content://apptwin.fixture/reply");
        RemoteInput.addDataResultToIntent(input, wrapper, Collections.singletonMap("image/png", uri));
        RemoteInput.setResultsSource(wrapper, RemoteInput.SOURCE_CHOICE);
        // Unrelated envelope extras must not become original intent extras.
        wrapper.getClipData().getItemAt(0).getIntent().putExtra("untrusted", "outer");
        Intent original = original();
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, original, "service");
        assertNotNull(result);
        assertEquals(uri, RemoteInput.getDataResultsFromIntent(result, input.getResultKey()).get("image/png"));
        assertEquals(RemoteInput.SOURCE_CHOICE, RemoteInput.getResultsSource(result));
        assertFalse(result.hasExtra("untrusted"));
        assertFalse(result.getClipData().getItemAt(0).getIntent().hasExtra("untrusted"));
        assertRouting(original, result);
        assertNull(original.getClipData());
    }

    @Test public void keepsExistingDataWhenAddingText() {
        assumeTrue(Build.VERSION.SDK_INT >= 26);
        Intent original = original();
        Uri uri = Uri.parse("content://apptwin.fixture/existing");
        RemoteInput.addDataResultToIntent(input, original, Collections.singletonMap("image/png", uri));
        Intent wrapper = new Intent();
        Bundle text = new Bundle();
        text.putCharSequence(input.getResultKey(), "new reply");
        RemoteInput.addResultsToIntent(new RemoteInput[]{input}, wrapper, text);
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, original, "receiver");
        assertEquals(uri, RemoteInput.getDataResultsFromIntent(result, input.getResultKey()).get("image/png"));
        assertNull(RemoteInput.getResultsFromIntent(original));
    }

    @Test public void ignoresArbitraryClipDataAndOuterExtras() {
        Intent wrapper = new Intent().putExtra("routing", "attacker");
        wrapper.setClipData(ClipData.newPlainText("fixture", "unrelated"));
        Intent original = original();
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, original, "receiver");
        assertRouting(original, result);
        assertNull(result.getClipData());
        assertNull(RemoteInput.getResultsFromIntent(result));
    }

    @Test public void repliesAreIndependentAcrossTwoTargets() {
        Intent one = original().putExtra("conversation", "one");
        Intent two = original().putExtra("conversation", "two");
        Intent wrapper = new Intent();
        Bundle text = new Bundle();
        text.putCharSequence(input.getResultKey(), "one reply");
        RemoteInput.addResultsToIntent(new RemoteInput[]{input}, wrapper, text);
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, one, "receiver");
        assertEquals("one", result.getStringExtra("conversation"));
        assertEquals("two", two.getStringExtra("conversation"));
        assertNull(RemoteInput.getResultsFromIntent(two));
    }

    @Test public void overwritingExistingTextDoesNotMutateOriginal() {
        Intent original = original();
        Bundle oldText = new Bundle();
        oldText.putCharSequence(input.getResultKey(), "old");
        RemoteInput.addResultsToIntent(new RemoteInput[]{input}, original, oldText);
        Intent wrapper = new Intent();
        Bundle newText = new Bundle();
        newText.putCharSequence(input.getResultKey(), "new");
        RemoteInput.addResultsToIntent(new RemoteInput[]{input}, wrapper, newText);
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, original, "receiver");
        assertEquals("new", RemoteInput.getResultsFromIntent(result).getCharSequence(input.getResultKey()));
        assertEquals("old", RemoteInput.getResultsFromIntent(original).getCharSequence(input.getResultKey()));
    }

    @Test public void overwritingExistingDataDoesNotMutateOriginal() {
        assumeTrue(Build.VERSION.SDK_INT >= 26);
        Intent original = original();
        Uri oldUri = Uri.parse("content://apptwin.fixture/old");
        Uri newUri = Uri.parse("content://apptwin.fixture/new");
        RemoteInput.addDataResultToIntent(input, original, Collections.singletonMap("image/png", oldUri));
        Intent wrapper = new Intent();
        RemoteInput.addDataResultToIntent(input, wrapper, Collections.singletonMap("image/png", newUri));
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, original, "service");
        assertEquals(newUri, RemoteInput.getDataResultsFromIntent(result, input.getResultKey()).get("image/png"));
        assertEquals(oldUri, RemoteInput.getDataResultsFromIntent(original, input.getResultKey()).get("image/png"));
    }

    @Test public void preservesMultipleResultKeysAndEmptyText() {
        RemoteInput second = new RemoteInput.Builder("second-fixture").build();
        Intent wrapper = new Intent();
        Bundle text = new Bundle();
        text.putCharSequence(input.getResultKey(), "");
        text.putCharSequence(second.getResultKey(), "second reply");
        RemoteInput.addResultsToIntent(new RemoteInput[]{input, second}, wrapper, text);
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, original(), "receiver");
        Bundle copied = RemoteInput.getResultsFromIntent(result);
        assertEquals(2, copied.size());
        assertEquals("", copied.getCharSequence(input.getResultKey()));
        assertEquals("second reply", copied.getCharSequence(second.getResultKey()));
    }

    @Test public void ordinaryNullLabelIntentClipDoesNotDropAction() {
        Intent wrapper = new Intent();
        wrapper.setClipData(ClipData.newIntent(null, new Intent("apptwin.fixture.UNRELATED")));
        Intent original = original();
        original.setClipData(ClipData.newIntent(null, new Intent("apptwin.fixture.ORIGINAL")));
        Intent result = PendingIntentRemoteInput.copyResults(wrapper, original, "service");
        assertNotNull(result);
        assertRouting(original, result);
        assertEquals("apptwin.fixture.ORIGINAL", result.getClipData().getItemAt(0).getIntent().getAction());
    }

    private static Intent original() {
        return new Intent("apptwin.fixture.REPLY")
                .setComponent(new ComponentName("apptwin.fixture", "apptwin.fixture.ReplyReceiver"))
                .setData(Uri.parse("apptwin-fixture://conversation/one"))
                .addCategory("apptwin.fixture.CATEGORY")
                .putExtra("routing", "trusted").putExtra("_VA_|_user_id_", 2);
    }

    private static void assertRouting(Intent original, Intent result) {
        assertTrue(original.filterEquals(result));
        assertEquals("trusted", result.getStringExtra("routing"));
        assertEquals(2, result.getIntExtra("_VA_|_user_id_", -1));
        assertEquals(original.getFlags(), result.getFlags());
    }
}
