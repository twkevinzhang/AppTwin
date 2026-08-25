package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class LinePushHookDiagnosticsSourceTest {
    @Test
    public void hookRecordsTheBoundedSystemBroadcastHandoffStages() throws Exception {
        String hooks = read("MethodProxies.java");
        int broadcast = hooks.indexOf("static class BroadcastIntent");
        int nextClass = hooks.indexOf("static class GetActivityClassForToken", broadcast);
        String path = hooks.substring(broadcast, nextClass);

        assertTrue(path.indexOf("LinePushHookDiagnostics.exactScope(")
                < path.indexOf("LinePushHookDiagnostics.hookEntry("));
        assertTrue(path.indexOf("onSendBroadcast(intent)")
                < path.indexOf("LinePushHookDiagnostics.exactScope(",
                        path.indexOf("onSendBroadcast(intent)")));
        assertOrdered(path,
                "LinePushHookDiagnostics.hookEntry(",
                "issueLinePushBroadcastAttestation(",
                "LinePushHookDiagnostics.attestation(",
                "ComponentUtils.redirectBroadcastIntent(",
                "LinePushHookDiagnostics.wrapperCreated(");
        assertTrue(path.contains("LinePushHookDiagnostics.systemInvokeResult("));
        assertTrue(path.contains("LinePushHookDiagnostics.systemInvokeException("));
    }

    @Test
    public void diagnosticsCannotAcceptOrReadPayloadTokenOrExtras() throws Exception {
        String diagnostics = read("LinePushHookDiagnostics.java");

        assertFalse(diagnostics.contains("android.content.Intent"));
        assertFalse(diagnostics.contains("android.os.Bundle"));
        assertFalse(diagnostics.contains("getExtras("));
        assertFalse(diagnostics.contains("getStringExtra("));
        assertFalse(diagnostics.contains("getParcelableExtra("));
        assertFalse(diagnostics.contains("getMessage("));
        assertFalse(diagnostics.contains("Throwable"));
        assertTrue(diagnostics.contains("sender=gms target=line action=c2dm"));
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int current = source.indexOf(needle, previous + 1);
            assertTrue("missing or out-of-order: " + needle, current > previous);
            previous = current;
        }
    }

    private static String read(String fileName) throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/lody/virtual/client/hook/proxies/am/" + fileName)),
                StandardCharsets.UTF_8);
    }
}
