package com.lody.virtual.server.am;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded server-owned request lifetime independent of a forwarding Activity's lifetime. */
final class NativeAccountPickerSessions<T> {
    static final long LIFETIME_MS = 120_000;
    private static final int MAX_SESSIONS = 64;
    interface ScopeVerifier<T> { boolean isCurrent(Scope<T> scope); }
    static final class Scope<T> {
        final T owner;
        final Object client;
        final long processGeneration;
        final int user;
        final String packageName;
        final int serial;
        final long userEpoch;
        final long packageEpoch;
        final long deadline;
        boolean claimed;
        Scope(T owner, Object client, long generation, int user, String pkg, int serial,
                long userEpoch, long packageEpoch, long deadline) {
            this.owner = owner; this.client = client; this.processGeneration = generation;
            this.user = user; this.packageName = pkg; this.serial = serial;
            this.userEpoch = userEpoch; this.packageEpoch = packageEpoch; this.deadline = deadline;
        }
    }
    private final Map<String, Scope<T>> sessions = new HashMap<>();
    private final ScopeVerifier<T> verifier;
    NativeAccountPickerSessions(ScopeVerifier<T> verifier) { this.verifier = verifier; }

    String issue(T owner, Object client, long generation, int user, String pkg,
            int serial, long userEpoch, long packageEpoch, long now) {
        prune(now);
        if (owner == null || client == null || generation < 0 || user < 0 || pkg == null
                || pkg.isEmpty() || serial < 0 || packageEpoch == 0 || now < 0) return null;
        Scope<T> scope = new Scope<>(owner, client, generation, user, pkg, serial,
                userEpoch, packageEpoch, now + LIFETIME_MS);
        if (!verifier.isCurrent(scope)) return null;
        synchronized (this) {
            if (sessions.size() >= MAX_SESSIONS) return null;
            String id = UUID.randomUUID().toString();
            sessions.put(id, scope);
            return id;
        }
    }
    int claim(String id, long now) {
        Scope<T> scope = valid(id, now);
        synchronized (this) {
            if (scope == null || sessions.get(id) != scope || scope.claimed) return -1;
            scope.claimed = true;
            return scope.user;
        }
    }
    int current(String id, long now) {
        Scope<T> scope = valid(id, now);
        synchronized (this) {
            return scope != null && sessions.get(id) == scope && scope.claimed ? scope.user : -1;
        }
    }
    boolean complete(String id, long now) {
        Scope<T> scope = valid(id, now);
        synchronized (this) {
            if (scope == null || sessions.get(id) != scope) return false;
            sessions.remove(id);
            return scope.claimed;
        }
    }
    synchronized void retireOwner(T owner) {
        java.util.Iterator<Scope<T>> entries = sessions.values().iterator();
        while (entries.hasNext()) {
            if (entries.next().owner == owner) entries.remove();
        }
    }
    synchronized void retireUser(int user) {
        java.util.Iterator<Scope<T>> entries = sessions.values().iterator();
        while (entries.hasNext()) {
            if (entries.next().user == user) entries.remove();
        }
    }
    private Scope<T> valid(String id, long now) {
        Scope<T> scope;
        synchronized (this) { scope = sessions.get(id); }
        if (scope == null) return null;
        // Never invoke Android/server authority while holding this registry's monitor.
        boolean current = now >= 0 && now < scope.deadline && verifier.isCurrent(scope);
        synchronized (this) {
            if (sessions.get(id) != scope) return null;
            if (!current) { sessions.remove(id); return null; }
            return scope;
        }
    }
    private void prune(long now) {
        java.util.List<String> ids;
        synchronized (this) { ids = new java.util.ArrayList<>(sessions.keySet()); }
        for (String id : ids) valid(id, now);
    }
}
