package com.lody.virtual.client.stub;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class OutboundFileStoreTest {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final long HOUR = 60L * 60L * 1000L;

    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private OutboundFileStore store(long cap) throws IOException {
        return new OutboundFileStore(temp.getRoot(), now::get, cap, mime -> XLSX.equals(mime) ? "xlsx" : null);
    }

    private File write(OutboundFileStore store, String name, int bytes) throws IOException {
        File file = store.create(3, name, XLSX);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[bytes]); }
        store.touch(file);
        return file;
    }

    @Test public void sanitizeNameStripsPathAndFallsBack() {
        assertEquals("b.xlsx", OutboundFileStore.sanitizeName("../a/b.xlsx"));
        assertEquals("file", OutboundFileStore.sanitizeName(""));
        assertEquals("file", OutboundFileStore.sanitizeName(null));
        assertEquals("file", OutboundFileStore.sanitizeName(".."));
    }

    @Test public void sanitizeNameTruncatesKeepingExtension() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 116; i++) raw.append('a');
        raw.append(".xlsx");
        assertEquals(121, raw.length());
        String name = OutboundFileStore.sanitizeName(raw.toString());
        assertEquals(120, name.length());
        assertTrue(name.endsWith(".xlsx"));
    }

    @Test public void sanitizeNameLimitsUtf8BytesForCjk() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 90; i++) raw.append('資');
        raw.append(".xlsx");
        String name = OutboundFileStore.sanitizeName(raw.toString());
        assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= OutboundFileStore.MAX_NAME_BYTES);
        assertTrue(name.endsWith(".xlsx"));
        assertTrue(name.startsWith("資"));
    }

    @Test public void sanitizeNameNeverSplitsSurrogatePairs() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 80; i++) raw.append("😀");
        raw.append(".pdf");
        String name = OutboundFileStore.sanitizeName(raw.toString());
        assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= OutboundFileStore.MAX_NAME_BYTES);
        String stem = name.substring(0, name.length() - ".pdf".length());
        assertEquals(0, stem.length() % 2);
        for (int i = 0; i < stem.length(); i += 2) {
            assertTrue(Character.isSurrogatePair(stem.charAt(i), stem.charAt(i + 1)));
        }
    }

    @Test public void createStaysUnderUserToken() throws IOException {
        File file = store(Long.MAX_VALUE).create(3, "x.xlsx", XLSX);
        String relative = store(Long.MAX_VALUE).relativePath(file);
        assertTrue(relative, relative.matches("3/[0-9a-f-]{36}/x\\.xlsx"));
    }

    @Test public void createAddsExtensionFromMime() throws IOException {
        File file = store(Long.MAX_VALUE).create(3, "report", XLSX);
        assertEquals("report.xlsx", file.getName());
    }

    @Test public void resolveRejectsTraversal() throws IOException {
        OutboundFileStore store = store(Long.MAX_VALUE);
        File file = write(store, "x.xlsx", 1);
        assertEquals(file.getCanonicalFile(), store.resolve(store.relativePath(file)));
        assertNull(store.resolve("../../etc/passwd"));
        assertNull(store.resolve("3/../../x"));
        assertNull(store.resolve("3"));
    }

    @Test public void sweepDeletesExpired() throws IOException {
        OutboundFileStore store = store(Long.MAX_VALUE);
        File fresh = write(store, "keep.xlsx", 1);
        now.addAndGet(23 * HOUR);
        store.sweep();
        assertTrue(fresh.exists());
        now.addAndGet(HOUR + 1);
        store.sweep();
        assertFalse(fresh.exists());
        assertFalse(fresh.getParentFile().exists());
    }

    @Test public void sweepEnforcesTotalCap() throws IOException {
        OutboundFileStore store = store(10);
        File oldest = write(store, "a.xlsx", 4);
        now.addAndGet(1000);
        File middle = write(store, "b.xlsx", 4);
        now.addAndGet(1000);
        File newest = write(store, "c.xlsx", 4);
        store.sweep();
        assertFalse(oldest.exists());
        assertTrue(middle.exists());
        assertTrue(newest.exists());
    }
}
