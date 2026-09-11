package com.lody.virtual.client.hook.proxies.media.session;

import static org.junit.Assert.*;

import org.junit.Test;

public class MediaKeyCallerIdentityPolicyTest {
    private static final String GUEST = "app.revanced.android.youtube";
    private static final String HOST = "org.apptwin";
    private static final String[] TYPES = {"java.lang.String", "android.view.KeyEvent",
            "android.media.session.MediaSession$Token"};

    @Test
    public void physicalCallerChangesWithoutChangingEventOrTarget() {
        Object event = new Object();
        Object token = new Object();
        Object[] args = {GUEST, event, token};
        rewrite(args);
        assertEquals(HOST, args[0]);
        assertSame(event, args[1]);
        assertSame(token, args[2]);
    }

    @Test
    public void hostAndUnrelatedCallersRemainUnchanged() {
        for (String caller : new String[]{HOST, "other.package", null}) {
            Object[] args = {caller, new Object(), new Object()};
            rewrite(args);
            assertEquals(caller, args[0]);
        }
    }

    @Test
    public void differentMethodOrChangedFrameworkShapeIsNotRewritten() {
        Object[] args = {GUEST, new Object(), new Object()};
        MediaKeyCallerIdentityPolicy.rewriteCaller("dispatchMediaKeyEvent", "boolean",
                TYPES, args, GUEST, HOST);
        assertEquals(GUEST, args[0]);
        MediaKeyCallerIdentityPolicy.rewriteCaller(MediaKeyCallerIdentityPolicy.METHOD, "void",
                TYPES, args, GUEST, HOST);
        assertEquals(GUEST, args[0]);
        MediaKeyCallerIdentityPolicy.rewriteCaller(MediaKeyCallerIdentityPolicy.METHOD, "boolean",
                new String[]{"java.lang.String", "java.lang.String", TYPES[2]}, args, GUEST, HOST);
        assertEquals(GUEST, args[0]);
        Object[] shortArgs = {GUEST};
        rewrite(shortArgs);
        assertEquals(GUEST, shortArgs[0]);
    }

    @Test
    public void missingBoundIdentityOrArgumentsCannotTriggerRewrite() {
        Object[] args = {GUEST, new Object(), new Object()};
        MediaKeyCallerIdentityPolicy.rewriteCaller(MediaKeyCallerIdentityPolicy.METHOD, "boolean",
                TYPES, args, null, HOST);
        assertEquals(GUEST, args[0]);
        MediaKeyCallerIdentityPolicy.rewriteCaller(MediaKeyCallerIdentityPolicy.METHOD, "boolean",
                TYPES, args, GUEST, "");
        assertEquals(GUEST, args[0]);
        rewrite(null);
    }

    private static void rewrite(Object[] args) {
        MediaKeyCallerIdentityPolicy.rewriteCaller(MediaKeyCallerIdentityPolicy.METHOD, "boolean",
                TYPES, args, GUEST, HOST);
    }
}
