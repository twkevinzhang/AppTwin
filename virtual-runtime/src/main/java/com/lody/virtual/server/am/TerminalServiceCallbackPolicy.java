package com.lody.virtual.server.am;

/** Allows only no-op completion from an already retired token's original, now untracked process. */
final class TerminalServiceCallbackPolicy {
    static boolean mayIgnore(boolean terminalCompletion, boolean callerUntracked,
            boolean originalPidMatches, boolean tokenUserMatches, boolean tokenRetired,
            boolean recordStillActive, boolean hostOsUidMatches) {
        return terminalCompletion && callerUntracked && originalPidMatches && tokenUserMatches
                && tokenRetired && !recordStillActive && hostOsUidMatches;
    }
}
