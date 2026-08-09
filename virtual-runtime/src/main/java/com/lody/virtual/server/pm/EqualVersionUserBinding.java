package com.lody.virtual.server.pm;

import java.io.IOException;

/** Persists one equal-version user binding; externally visible effects happen after journal commit. */
final class EqualVersionUserBinding {
    interface InstalledState {
        boolean isInstalled(int userId);

        void setInstalled(int userId, boolean installed);
    }

    interface PersistenceCommit {
        void save() throws IOException;
    }

    static void bind(
            InstalledState state,
            int userId,
            PersistenceCommit persistence) throws IOException {
        update(state, userId, true, persistence);
    }

    static void unbind(
            InstalledState state,
            int userId,
            PersistenceCommit persistence) throws IOException {
        update(state, userId, false, persistence);
    }

    private static void update(
            InstalledState state,
            int userId,
            boolean installed,
            PersistenceCommit persistence) throws IOException {
        boolean previous = state.isInstalled(userId);
        if (previous == installed) return;
        state.setInstalled(userId, installed);
        try {
            persistence.save();
        } catch (IOException failure) {
            state.setInstalled(userId, previous);
            throw failure;
        }
    }

    private EqualVersionUserBinding() {
    }
}
