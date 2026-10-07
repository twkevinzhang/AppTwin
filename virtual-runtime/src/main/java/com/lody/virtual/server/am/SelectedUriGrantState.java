package com.lody.virtual.server.am;

import android.util.AtomicFile;
import com.lody.virtual.os.VEnvironment;
import java.io.*;
import java.util.HashSet;
import java.util.Set;

/** Only ActivityManager's trusted host-picker completion writes this server-owned store. */
final class SelectedUriGrantState {
    private final AtomicFile file = new AtomicFile(new File(VEnvironment.getSystemSecureDirectory(), "selected-uri-grants.bin"));
    private final Set<String> grants = new HashSet<>();
    SelectedUriGrantState() {
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (input.readInt() != 0x53555231) return;
            int count = input.readInt();
            if (count < 0 || count > 16384) return;
            Set<String> loaded = new HashSet<>();
            for (int i = 0; i < count; i++) loaded.add(input.readUTF());
            if (input.read() == -1) grants.addAll(loaded);
        } catch (IOException ignored) { grants.clear(); }
    }
    private static String key(int user, String pkg, String uri) { return user + "\n" + pkg + "\n" + uri; }
    synchronized boolean has(int user, String pkg, String uri) { return grants.contains(key(user, pkg, uri)); }
    synchronized void clear(int user, String pkg) throws IOException {
        Set<String> next = new HashSet<>(grants);
        String prefix = user + "\n" + (pkg == null ? "" : pkg + "\n");
        next.removeIf(entry -> entry.startsWith(prefix));
        write(next);
    }
    synchronized void add(int user, String pkg, Set<String> uris) throws IOException {
        Set<String> next = new HashSet<>(grants);
        for (String uri : uris) next.add(key(user, pkg, uri));
        write(next);
    }
    private void write(Set<String> next) throws IOException {
        if (next.size() > 16384) throw new IOException("Selected URI quota exceeded");
        FileOutputStream output = file.startWrite();
        try {
            DataOutputStream data = new DataOutputStream(output);
            data.writeInt(0x53555231); data.writeInt(next.size());
            for (String entry : next) data.writeUTF(entry);
            data.flush(); file.finishWrite(output);
            grants.clear(); grants.addAll(next);
        } catch (IOException failure) { file.failWrite(output); throw failure; }
    }
}
