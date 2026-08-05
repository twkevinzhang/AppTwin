package com.lody.virtual.client.stub;

/** Bundle protocol shared by the server and Stub process bootstrap provider. */
public final class StubProcessContract {

    public static final String METHOD_INIT_PROCESS = "_VA_|_init_process_";
    public static final String METHOD_QUERY_OWNER = "_VA_|_query_process_owner_";

    public static final String KEY_SERVER_TOKEN = "_VA_|_binder_";
    public static final String KEY_VUID = "_VA_|_vuid_";
    public static final String KEY_PACKAGE_NAME = "_VA_|_pkg_";
    public static final String KEY_PROCESS_NAME = "_VA_|_process_";
    public static final String KEY_GENERATION = "_VA_|_generation_";
    public static final String KEY_REPORTED_UID_OVERRIDE = "_VA_|_reported_uid_override_";

    public static final String KEY_ACCEPTED = "_VA_|_accepted_";
    public static final String KEY_REASON = "_VA_|_reason_";
    public static final String KEY_HAS_OWNER = "_VA_|_has_owner_";
    public static final String KEY_GUEST_BOUND = "_VA_|_guest_bound_";
    public static final String KEY_CLIENT = "_VA_|_client_";
    public static final String KEY_PID = "_VA_|_pid_";

    public static final String REASON_QUERY_CURRENT = "query_current";
    public static final String REASON_QUERY_EMPTY = "query_empty";

    private StubProcessContract() {
    }
}
