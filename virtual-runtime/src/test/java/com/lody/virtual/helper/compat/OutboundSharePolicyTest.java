package com.lody.virtual.helper.compat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class OutboundSharePolicyTest {
    private static final String VIRTUAL = "content://jp.naver.line.android.line.MessageContentProvider/a";
    private static final String HOST_MEDIA = "content://media/external/1";
    private static final Predicate<String> IS_VIRTUAL =
            uri -> uri.startsWith("content://jp.naver.line.android.");

    private static final class FakeRequest {
        final String action;
        final FakeRequest target;
        final List<String> uris;
        FakeRequest(String action, FakeRequest target, String... uris) {
            this.action = action;
            this.target = target;
            this.uris = Arrays.asList(uris);
        }
    }

    private static final OutboundSharePolicy.Request<FakeRequest> ADAPTER =
            new OutboundSharePolicy.Request<FakeRequest>() {
                @Override public String action(FakeRequest r) { return r.action; }
                @Override public FakeRequest target(FakeRequest r) { return r.target; }
                @Override public List<String> uris(FakeRequest r) { return r.uris; }
            };

    private static OutboundSharePolicy.Decision<FakeRequest> decide(FakeRequest r, boolean hasVirtualTarget) {
        return OutboundSharePolicy.decide(r, ADAPTER, IS_VIRTUAL, hasVirtualTarget);
    }

    @Test public void chooserWithVirtualStreamIsOutbound() {
        FakeRequest send = new FakeRequest("android.intent.action.SEND", null, VIRTUAL);
        FakeRequest chooser = new FakeRequest("android.intent.action.CHOOSER", send);
        OutboundSharePolicy.Decision<FakeRequest> d = decide(chooser, false);
        assertNotNull(d);
        assertTrue(d.chooser);
        assertSame(send, d.payload);
        assertEquals(Collections.singletonList(VIRTUAL), d.virtualUris);
    }

    @Test public void viewWithVirtualTargetStaysInside() {
        assertNull(decide(new FakeRequest("android.intent.action.VIEW", null, VIRTUAL), true));
    }

    @Test public void viewWithoutVirtualTargetIsOutbound() {
        FakeRequest view = new FakeRequest("android.intent.action.VIEW", null, VIRTUAL);
        OutboundSharePolicy.Decision<FakeRequest> d = decide(view, false);
        assertNotNull(d);
        assertFalse(d.chooser);
        assertSame(view, d.payload);
    }

    @Test public void mixedUrisOnlyVirtualCollected() {
        OutboundSharePolicy.Decision<FakeRequest> d = decide(
                new FakeRequest("android.intent.action.SEND_MULTIPLE", null, VIRTUAL, HOST_MEDIA, VIRTUAL), false);
        assertNotNull(d);
        assertEquals(Collections.singletonList(VIRTUAL), d.virtualUris);
    }

    @Test public void noVirtualUriIsNotOutbound() {
        assertNull(decide(new FakeRequest("android.intent.action.VIEW", null, HOST_MEDIA), false));
    }

    @Test public void otherActionIgnored() {
        assertNull(decide(new FakeRequest("android.intent.action.EDIT", null, VIRTUAL), false));
    }

    @Test public void chooserWithoutTargetIgnored() {
        assertNull(decide(new FakeRequest("android.intent.action.CHOOSER", null, VIRTUAL), false));
    }

    @Test public void tooManyVirtualUrisRejectedInsteadOfForwarded() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= OutboundSharePolicy.MAX_URIS; i++) many.add(VIRTUAL + i);
        assertEquals(33, many.size());
        OutboundSharePolicy.Decision<FakeRequest> d = decide(new FakeRequest(
                "android.intent.action.SEND_MULTIPLE", null, many.toArray(new String[0])), false);
        assertNotNull(d);
        assertTrue(d.rejected);
    }

    @Test public void tooManyHostOnlyUrisKeepExistingPath() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= OutboundSharePolicy.MAX_URIS; i++) many.add(HOST_MEDIA + i);
        assertNull(decide(new FakeRequest("android.intent.action.SEND_MULTIPLE", null,
                many.toArray(new String[0])), false));
    }

    @Test public void acceptedDecisionIsNotRejected() {
        OutboundSharePolicy.Decision<FakeRequest> d =
                decide(new FakeRequest("android.intent.action.VIEW", null, VIRTUAL), false);
        assertFalse(d.rejected);
    }
}
