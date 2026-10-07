package com.lody.virtual.server.accounts;

import android.accounts.Account;
import java.io.*;
import java.util.*;

/** Private account archive: no embedded destination user identity, no Java deserialization. */
public final class ScopedAccountArchive {
    public static final int MAX_BYTES = 256 * 1024;
    private static final int MAGIC = 0x41544143, VERSION = 1, MAX_ACCOUNTS = 64, MAX_ENTRIES = 128;
    private ScopedAccountArchive() {}

    public static byte[] encode(List<VAccount> accounts) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(MAGIC); out.writeInt(VERSION);
        count(accounts.size(), MAX_ACCOUNTS); out.writeInt(accounts.size());
        for (VAccount a : accounts) {
            string(out, a.name); string(out, a.type); string(out, a.previousName);
            string(out, a.password); out.writeLong(a.lastAuthenticatedTime);
            map(out, a.authTokens); map(out, a.userDatas);
        }
        if (buffer.size() > MAX_BYTES) throw new IOException("Account archive exceeds limit");
        return buffer.toByteArray();
    }

    public static List<VAccount> decode(byte[] bytes, int destinationUser) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES || destinationUser < 0)
            throw new IOException("Invalid account archive");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported account archive");
        int size = in.readInt(); count(size, MAX_ACCOUNTS);
        List<VAccount> accounts = new ArrayList<>();
        Set<Account> unique = new HashSet<>();
        for (int i = 0; i < size; i++) {
            String name = string(in), type = string(in);
            if (name == null || name.isEmpty() || type == null || type.isEmpty())
                throw new IOException("Invalid account identity");
            Account identity = new Account(name, type);
            if (!unique.add(identity)) throw new IOException("Duplicate account");
            VAccount a = new VAccount(destinationUser, identity);
            a.previousName = string(in); a.password = string(in); a.lastAuthenticatedTime = in.readLong();
            a.authTokens = map(in); a.userDatas = map(in); accounts.add(a);
        }
        if (in.available() != 0) throw new IOException("Trailing account archive data");
        return accounts;
    }

    private static void count(int n, int max) throws IOException {
        if (n < 0 || n > max) throw new IOException("Account archive count exceeds limit");
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        if (value != null && value.length() > 16384) throw new IOException("Account field exceeds limit");
        int worst = value == null ? 1 : 3 + value.length() * 3;
        if (out.size() + worst > MAX_BYTES) throw new IOException("Account archive exceeds limit");
        out.writeBoolean(value != null); if (value != null) out.writeUTF(value);
    }
    private static String string(DataInputStream in) throws IOException {
        String value = in.readBoolean() ? in.readUTF() : null;
        if (value != null && value.length() > 16384) throw new IOException("Account field exceeds limit");
        return value;
    }
    private static void map(DataOutputStream out, Map<String, String> map) throws IOException {
        count(map.size(), MAX_ENTRIES); out.writeInt(map.size());
        for (String key : new TreeSet<>(map.keySet())) { string(out, key); string(out, map.get(key)); }
    }
    private static Map<String, String> map(DataInputStream in) throws IOException {
        int count = in.readInt(); count(count, MAX_ENTRIES);
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String key = string(in), value = string(in);
            if (key == null || result.containsKey(key)) throw new IOException("Invalid account map key");
            result.put(key, value);
        }
        return result;
    }
}
