package org.apptwin.custodian.contract;

/** Stable, non-secret constants shared by AppTwin and the Custodian process. */
public final class CustodianContract {
    public static final String CUSTODIAN_PACKAGE = "org.apptwin.custodian";
    public static final String HOST_PACKAGE = "org.apptwin";
    public static final String ACTION_BIND = "org.apptwin.custodian.action.BIND";
    public static final String PERMISSION_BIND = "org.apptwin.permission.BIND_CUSTODIAN";

    public static final int PROTOCOL_VERSION = 2;

    public static final int HEALTH_READY = 1;
    public static final int HEALTH_STORE_UNAVAILABLE = 2;

    public static final String LINE_PACKAGE = "jp.naver.line.android";

    private CustodianContract() {
    }
}
