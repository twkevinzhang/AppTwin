package com.lody.virtual.server.am;

final class ScopedSyncLeasePolicy {
    static boolean valid(int user, int recordUser, long generation, long currentGeneration,
                         boolean live, boolean accountExists, boolean gate, long now, long deadline) {
        return user > 0 && user == recordUser && generation == currentGeneration
                && live && accountExists && gate && now < deadline;
    }
}
