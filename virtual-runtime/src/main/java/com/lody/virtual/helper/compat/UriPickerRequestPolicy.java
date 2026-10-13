package com.lody.virtual.helper.compat;

import android.content.Intent;

/** Only direct pickers or one chooser around a picker enter the trusted selection bridge. */
public final class UriPickerRequestPolicy {
    private static final String CHOOSER = "android.intent.action.CHOOSER";
    private UriPickerRequestPolicy() { }
    public interface Request<T> {
        String action(T request);
        T target(T request);
        boolean hasInitialIntents(T request);
        boolean isHostComponent(T request);
    }
    public static boolean isPickerAction(String action) {
        return "android.intent.action.GET_CONTENT".equals(action)
                || "android.intent.action.OPEN_DOCUMENT".equals(action)
                || "android.provider.action.PICK_IMAGES".equals(action);
    }
    public static <T> T extract(T request, Request<T> adapter) {
        if (request == null || adapter.isHostComponent(request)) return null;
        if (isPickerAction(adapter.action(request))) return request;
        if (!CHOOSER.equals(adapter.action(request)) || adapter.hasInitialIntents(request)) return null;
        T target = adapter.target(request);
        // No recursive unwrapping or additional initial intents: only the primary picker is trusted.
        if (target == null || adapter.isHostComponent(target)
                || !isPickerAction(adapter.action(target))) return null;
        return target;
    }
    public static Intent extract(Intent request, final String hostPackage) {
        try {
            return extract(request, new Request<Intent>() {
                @Override public String action(Intent value) { return value.getAction(); }
                @Override public Intent target(Intent value) {
                    Object target = value.getParcelableExtra(Intent.EXTRA_INTENT);
                    return target instanceof Intent ? (Intent) target : null;
                }
                @Override public boolean hasInitialIntents(Intent value) {
                    return value.hasExtra(Intent.EXTRA_INITIAL_INTENTS);
                }
                @Override public boolean isHostComponent(Intent value) {
                    return value.getComponent() != null
                            && hostPackage.equals(value.getComponent().getPackageName());
                }
            });
        } catch (RuntimeException malformed) { return null; }
    }
    /** Keep the original framework result token, resultWho and requestCode arguments intact. */
    public static void replaceLaunchIntent(Object[] args, int intentIndex, Object bridge) {
        args[intentIndex] = bridge;
        args[intentIndex + 1] = null;
    }
}
