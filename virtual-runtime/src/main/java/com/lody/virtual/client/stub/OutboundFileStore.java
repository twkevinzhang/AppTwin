package com.lody.virtual.client.stub;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Host-owned copies of guest files handed to apps outside the virtual environment.
 * Layout: {@code <cache>/outbound/<userId>/<token>/<name>}; one token directory per copy.
 */
public final class OutboundFileStore {
    public static final long TTL_MILLIS = 24L * 60L * 60L * 1000L;
    public static final long DEFAULT_CAP_BYTES = 500L * 1024L * 1024L;
    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_NAME_BYTES = 200;
    private static final int MAX_EXTENSION_LENGTH = 10;
    private static final String FALLBACK_NAME = "file";

    private final File root;
    private final LongSupplier clock;
    private final long capBytes;
    private final Function<String, String> extensionForMime;

    public OutboundFileStore(File cacheRoot, LongSupplier clock, long capBytes,
                             Function<String, String> extensionForMime) {
        this.root = new File(cacheRoot, "outbound");
        this.clock = clock;
        this.capBytes = capBytes;
        this.extensionForMime = extensionForMime;
    }

    public synchronized File create(int userId, String displayName, String mime) throws IOException {
        String name = sanitizeName(displayName);
        if (name.lastIndexOf('.') <= 0 && mime != null) {
            String extension = extensionForMime.apply(mime);
            if (extension != null && !extension.isEmpty()) name = sanitizeName(name + "." + extension);
        }
        File token = new File(new File(root, Integer.toString(userId)), UUID.randomUUID().toString());
        if (!token.mkdirs()) throw new IOException("Unable to create outbound directory");
        token.setLastModified(clock.getAsLong());
        return new File(token, name);
    }

    /** Marks a finished copy as fresh for {@link #sweep()}. */
    public void touch(File file) {
        long now = clock.getAsLong();
        file.setLastModified(now);
        File token = file.getParentFile();
        if (token != null) token.setLastModified(now);
    }

    /** @return the stored file, or null when the path leaves the store or is not a file. */
    public File resolve(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        try {
            File base = root.getCanonicalFile();
            File file = new File(base, relativePath).getCanonicalFile();
            if (!file.getPath().startsWith(base.getPath() + File.separator)) return null;
            return file.isFile() ? file : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    public String relativePath(File file) {
        String base = root.getAbsolutePath() + File.separator;
        String path = file.getAbsolutePath();
        if (!path.startsWith(base)) throw new IllegalArgumentException("Outside outbound store");
        return path.substring(base.length()).replace(File.separatorChar, '/');
    }

    public synchronized void sweep() {
        long now = clock.getAsLong();
        List<File> tokens = new ArrayList<>();
        File[] users = root.listFiles();
        if (users == null) return;
        for (File user : users) {
            File[] entries = user.listFiles();
            if (entries == null) continue;
            for (File token : entries) {
                if (now - token.lastModified() > TTL_MILLIS) deleteTree(token);
                else tokens.add(token);
            }
        }
        long total = 0;
        for (File token : tokens) total += size(token);
        tokens.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File token : tokens) {
            if (total <= capBytes) break;
            total -= size(token);
            deleteTree(token);
        }
    }

    public static String sanitizeName(String raw) {
        if (raw == null) return FALLBACK_NAME;
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7f) clean.append(c);
        }
        name = clean.toString().trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return FALLBACK_NAME;
        if (name.length() <= MAX_NAME_LENGTH && utf8Length(name) <= MAX_NAME_BYTES) return name;
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= MAX_EXTENSION_LENGTH + 1 ? name.substring(dot) : "";
        String stem = name.substring(0, name.length() - extension.length());
        int maxChars = MAX_NAME_LENGTH - extension.length();
        int maxBytes = MAX_NAME_BYTES - utf8Length(extension);
        // Filesystems limit names in bytes (NAME_MAX 255); cut on code-point boundaries only.
        StringBuilder kept = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < stem.length(); ) {
            int cp = stem.codePointAt(i);
            String piece = new String(Character.toChars(cp));
            int pieceBytes = utf8Length(piece);
            if (kept.length() + piece.length() > maxChars || bytes + pieceBytes > maxBytes) break;
            kept.append(piece);
            bytes += pieceBytes;
            i += Character.charCount(cp);
        }
        return kept.length() == 0 ? FALLBACK_NAME + extension : kept + extension;
    }

    private static int utf8Length(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static long size(File file) {
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
