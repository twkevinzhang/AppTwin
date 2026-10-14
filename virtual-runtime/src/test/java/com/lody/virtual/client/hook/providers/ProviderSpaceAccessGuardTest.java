package com.lody.virtual.client.hook.providers;

import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class ProviderSpaceAccessGuardTest {
    private static final String READ = "android.permission.READ_CONTACTS";
    private static final String WRITE = "android.permission.WRITE_CONTACTS";
    private static final String STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static final class Selected {
        final String authority, path;
        Selected(String authority, String path) { this.authority = authority; this.path = path; }
    }
    private static final class Environment implements ProviderSpaceAccessEnvironment {
        int sdk = 32;
        boolean selectedRead, hostRead, hostWrite, mediaRead, throwSource;
        Object lastSelected, lastHost;
        boolean images, videos;
        final Set<String> grants = new HashSet<>();
        final List<String> required = new ArrayList<>();
        @Override public boolean isUri(Object value) { return value instanceof Selected; }
        @Override public String authority(Object uri) { return ((Selected) uri).authority; }
        @Override public String path(Object uri) { return ((Selected) uri).path; }
        @Override public int sdk() { return sdk; }
        @Override public void require(String permission) {
            required.add(permission);
            if (!isGranted(permission)) throw new SecurityException(permission);
        }
        @Override public boolean isGranted(String permission) { return grants.contains(permission); }
        @Override public boolean canReadMedia(boolean images, boolean videos) {
            this.images = images; this.videos = videos; return mediaRead;
        }
        @Override public boolean hasSelectedRead(Object uri) {
            if (throwSource) throw new IllegalStateException("capability unavailable");
            lastSelected = uri; return selectedRead;
        }
        @Override public boolean hasHostRead(Object uri) { lastHost = uri; return hostRead; }
        @Override public boolean hasHostWrite(Object uri) { lastHost = uri; return hostWrite; }
        @Override public void documentDenied(String method, boolean hasUri) { }
    }
    private static void enforce(Environment env, String authority, String method, Object... args) {
        new ProviderSpaceAccessGuard(env).enforce(authority, method, args);
    }
    private static void denied(Environment env, String authority, String method, Object... args) {
        boolean forwarded = false;
        try { enforce(env, authority, method, args); forwarded = true; fail("Expected denial"); }
        catch (SecurityException expected) { assertFalse(forwarded); }
    }
    @Test public void contactsAliasesReadAndWriteAreDirectional() {
        Environment env = new Environment(); env.grants.add(READ);
        enforce(env, "com.android.contacts;contacts", "query");
        denied(env, "contacts", "update");
        env.grants.add(WRITE); enforce(env, "contacts", "update");
        assertEquals(READ, env.required.get(0));
        assertEquals(WRITE, env.required.get(1));
    }
    @Test public void contactsUnknownCallAndBatchRequireBothCapabilities() {
        for (String method : new String[]{"call", "applyBatch"}) {
            Environment env = new Environment(); env.grants.add(READ);
            denied(env, "contacts", method);
            assertEquals(java.util.Arrays.asList(READ, WRITE), env.required);
            env.grants.add(WRITE); enforce(env, "contacts", method);
        }
    }
    @Test public void contactsFirstUriAndFirstFollowingStringDetermineAccess() {
        Environment env = new Environment(); env.grants.add(READ);
        enforce(env, "other", "openFile", new Selected("10@contacts", "/"), "r",
                new Selected("media", "/images"), "w");
        assertEquals(java.util.Arrays.asList(READ), env.required);
        Environment ignored = new Environment();
        enforce(ignored, "other", "query", new Selected("other", "/"), new Selected("contacts", "/"));
        assertTrue(ignored.required.isEmpty());
    }
    @Test public void binderAndNullAcquisitionAuthorityPreserveBypass() {
        Environment env = new Environment(); env.throwSource = true;
        enforce(env, "media", "asBinder", new Selected("media", "/images"));
        enforce(env, null, "query", new Selected("contacts", "/"));
        assertTrue(env.required.isEmpty());
    }
    @Test public void documentRequiresSpaceReadAndLegacyHostWrite() {
        Selected uri = new Selected("files.documents", "/document/1");
        Environment env = new Environment(); env.grants.add(STORAGE);
        denied(env, "files.documents", "openFile", uri, "w");
        env.selectedRead = true;
        denied(env, "files.documents", "openFile", uri, "w");
        env.hostWrite = true; env.grants.clear();
        denied(env, "files.documents", "openFile", uri, "w");
        env.grants.add(STORAGE); enforce(env, "files.documents", "openFile", uri, "w");
    }
    @Test public void documentsUseLastUriButKeepFirstFollowingMode() {
        Selected first = new Selected("files.documents", "/first");
        Selected last = new Selected("files.documents", "/last");
        Environment env = new Environment(); env.selectedRead = true;
        enforce(env, "files.documents", "openFile", first, "r", last, "w");
        assertSame(last, env.lastSelected); assertTrue(env.required.isEmpty());
        env.hostWrite = true; env.grants.add(STORAGE);
        enforce(env, "files.documents", "openFile", first, "w", last, "r");
        assertSame(last, env.lastHost); assertEquals(java.util.Arrays.asList(STORAGE), env.required);
    }
    @Test public void modernDocumentsDoNotAddLegacyWriteCapability() {
        Environment env = new Environment(); env.sdk = 33; env.selectedRead = true;
        enforce(env, "photopicker", "applyBatch", new Selected("photopicker", "/1"));
        assertTrue(env.required.isEmpty()); assertNull(env.lastHost);
    }
    @Test public void mediaSelectedReadRequiresBothSpaceAndHostGrant() {
        Selected uri = new Selected("media", "/images/1");
        for (int sdk : new int[]{32, 33}) {
            Environment env = new Environment(); env.sdk = sdk; env.selectedRead = true;
            denied(env, "media", "query", uri);
            env.selectedRead = false; env.hostRead = true; denied(env, "media", "query", uri);
            env.selectedRead = true; enforce(env, "media", "query", uri);
        }
    }
    @Test public void legacyMutationNeedsWriteButModernScopedPolicyIsPreserved() {
        Selected uri = new Selected("media", "/images/1");
        Environment env = new Environment(); env.mediaRead = true;
        denied(env, "media", "update", uri);
        denied(env, "media", "unknownBatch", uri);
        env.grants.add(STORAGE); enforce(env, "media", "unknownBatch", uri);
        env.grants.clear(); env.sdk = 33; enforce(env, "media", "update", uri);
    }
    @Test public void mediaUsesFirstUriAndAudioReadExemption() {
        Environment env = new Environment();
        Selected first = new Selected("media", "/audio/1");
        enforce(env, "media", "query", first, new Selected("media", "/images/2"));
        assertSame(first, env.lastSelected);
        denied(env, "media", "delete", first);
        env.grants.add(STORAGE); enforce(env, "media", "delete", first);
    }
    @Test public void permissionSourcesAreFreshAndExceptionsDoNotForward() {
        Environment env = new Environment(); env.grants.add(READ);
        enforce(env, "contacts", "query"); env.grants.clear(); denied(env, "contacts", "query");
        env.throwSource = true; boolean forwarded = false;
        try { enforce(env, "media", "query", new Selected("media", "/images")); forwarded = true; fail(); }
        catch (IllegalStateException expected) { assertFalse(forwarded); }
    }
    @Test public void imageAndVideoReadFlagsRemainScoped() {
        Environment env = new Environment(); env.mediaRead = true;
        enforce(env, "media", "query", new Selected("media", "/images/1"));
        assertTrue(env.images); assertFalse(env.videos);
        enforce(env, "media", "query", new Selected("media", "/video/1"));
        assertFalse(env.images); assertTrue(env.videos);
    }
    public static final class RecordingProvider {
        int calls;
        public Object query(Object uri) { calls++; return null; }
    }
    @Test public void actualInvocationDenialPrecedesRewritingAndBaseForwarding() throws Throwable {
        Environment env = new Environment();
        RecordingProvider base = new RecordingProvider();
        final int[] rewrites = {0};
        ProviderHook hook = new ProviderHook(base, env) {
            @Override protected void processArgs(java.lang.reflect.Method method, Object[] args) {
                rewrites[0]++;
            }
        };
        java.lang.reflect.Field authority = ProviderHook.class.getDeclaredField("permissionAuthority");
        authority.setAccessible(true); authority.set(hook, "contacts");
        try {
            hook.invoke(null, RecordingProvider.class.getMethod("query", Object.class), new Object[]{null});
            fail("Denied invocation must not return");
        } catch (SecurityException expected) {
            assertEquals(0, rewrites[0]); assertEquals(0, base.calls);
        }
    }

}
