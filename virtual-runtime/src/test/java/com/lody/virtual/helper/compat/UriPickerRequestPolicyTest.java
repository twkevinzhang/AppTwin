package com.lody.virtual.helper.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public class UriPickerRequestPolicyTest {
    private static class Node {
        final String action;
        Node target;
        boolean initial;
        boolean host;
        Node(String action) { this.action = action; }
    }
    private final UriPickerRequestPolicy.Request<Node> adapter = new UriPickerRequestPolicy.Request<Node>() {
        public String action(Node node) { return node.action; }
        public Node target(Node node) { return node.target; }
        public boolean hasInitialIntents(Node node) { return node.initial; }
        public boolean isHostComponent(Node node) { return node.host; }
    };
    private Node chooser(Node target) {
        Node wrapper = new Node("android.intent.action.CHOOSER");
        wrapper.target = target;
        return wrapper;
    }
    @Test public void directAndOneChooserSupportOnlyTheThreePickerActions() {
        for (String action : new String[]{"android.intent.action.GET_CONTENT",
                "android.intent.action.OPEN_DOCUMENT", "android.provider.action.PICK_IMAGES"}) {
            Node picker = new Node(action);
            assertSame(picker, UriPickerRequestPolicy.extract(picker, adapter));
            assertSame(picker, UriPickerRequestPolicy.extract(chooser(picker), adapter));
        }
    }
    @Test public void nestedMissingOrUnsupportedTargetsRemainOnOriginalRoute() {
        assertNull(UriPickerRequestPolicy.extract(chooser(chooser(new Node("android.intent.action.GET_CONTENT"))), adapter));
        assertNull(UriPickerRequestPolicy.extract(chooser(null), adapter));
        assertNull(UriPickerRequestPolicy.extract(chooser(new Node("android.intent.action.SEND")), adapter));
        assertNull(UriPickerRequestPolicy.extract(new Node(null), adapter));
        assertNull(UriPickerRequestPolicy.extract(null, adapter));
    }
    @Test public void chooserInitialIntentsCannotEnterTrustedRoute() {
        Node request = chooser(new Node("android.intent.action.GET_CONTENT"));
        request.initial = true;
        assertNull(UriPickerRequestPolicy.extract(request, adapter));
    }
    @Test public void hostComponentsCannotReenterBridge() {
        Node picker = new Node("android.intent.action.GET_CONTENT");
        picker.host = true;
        assertNull(UriPickerRequestPolicy.extract(picker, adapter));
        assertNull(UriPickerRequestPolicy.extract(chooser(picker), adapter));
        Node request = chooser(new Node("android.intent.action.GET_CONTENT"));
        request.host = true;
        assertNull(UriPickerRequestPolicy.extract(request, adapter));
    }
    @Test public void replacingIntentKeepsOriginalResultOwnerAndRequestIdentity() {
        Object caller = new Object(), oldIntent = new Object(), token = new Object(), options = new Object(), bridge = new Object();
        Object[] args = {caller, "guest", "feature", oldIntent, "*/*", token, "who", 1141056187, 0, options};
        Object[] original = args.clone();
        UriPickerRequestPolicy.replaceLaunchIntent(args, 3, bridge);
        assertSame(bridge, args[3]);
        assertNull(args[4]);
        for (int i = 0; i < args.length; i++) if (i != 3 && i != 4) assertSame(original[i], args[i]);
        assertSame(oldIntent, original[3]);
    }
}
