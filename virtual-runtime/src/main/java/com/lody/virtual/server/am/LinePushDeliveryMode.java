package com.lody.virtual.server.am;

/** Selects the cloned-LINE delivery strategy without weakening its exact route checks. */
enum LinePushDeliveryMode {
    /** Stage-one baseline: dispatch only while the ordinary daemon workload gate is open. */
    DIRECT_BASELINE,

    /** Stage-two pipeline: eligible closed-gate deliveries may request bounded daemon recovery. */
    RELIABLE_GATED
}
