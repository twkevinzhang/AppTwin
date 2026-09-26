package com.lody.virtual.client.stub;

/** Exact physical binding exception; arbitrary host or guest services retain normal routing. */
public final class ForegroundEngineBindingPolicy {
    public static final String SERVICE_CLASS =
            "com.lody.virtual.client.stub.ForegroundEngineService";
    public static final long TRANSITION_MILLIS = 400L;
    private ForegroundEngineBindingPolicy() {}
    public static boolean isEngineBinding(String hostPackage, String componentPackage,
            String componentClass) {
        return hostPackage != null && hostPackage.equals(componentPackage)
                && SERVICE_CLASS.equals(componentClass);
    }
}
