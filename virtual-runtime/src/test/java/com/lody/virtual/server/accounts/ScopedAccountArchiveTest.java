package com.lody.virtual.server.accounts;

import org.junit.Test;
import java.io.*;
import java.util.Collections;
import static org.junit.Assert.*;

public class ScopedAccountArchiveTest {
    @Test public void emptyArchiveRoundTripsWithoutDestinationIdentity() throws Exception {
        byte[] bytes = ScopedAccountArchive.encode(Collections.emptyList());
        assertEquals(12, bytes.length);
        assertTrue(ScopedAccountArchive.decode(bytes, 42).isEmpty());
    }
    @Test public void malformedCountsVersionTrailingAndOversizeFailClosed() throws Exception {
        byte[] valid = ScopedAccountArchive.encode(Collections.emptyList());
        byte[] wrongVersion = valid.clone(); wrongVersion[7] = 2;
        rejected(wrongVersion);
        byte[] negative = valid.clone(); negative[8] = (byte) 0xff;
        rejected(negative);
        byte[] tooMany = valid.clone(); tooMany[11] = 65;
        rejected(tooMany);
        rejected(java.util.Arrays.copyOf(valid, valid.length + 1));
        rejected(new byte[ScopedAccountArchive.MAX_BYTES + 1]);
        rejected(new byte[0]);
    }
    private static void rejected(byte[] bytes) {
        try { ScopedAccountArchive.decode(bytes, 42); fail("Malformed archive accepted"); }
        catch (IOException expected) { }
    }
}
