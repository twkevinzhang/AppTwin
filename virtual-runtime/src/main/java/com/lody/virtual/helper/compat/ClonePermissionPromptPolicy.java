package com.lody.virtual.helper.compat;

import com.lody.virtual.client.hook.GuestPermissionPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure decisions for the AppTwin-owned permission prompt shown when a clone requests permissions. */
public final class ClonePermissionPromptPolicy {
    public static final String ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS";
    public static final String EXTRA_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    public static final String EXTRA_RESULTS = "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS";

    private static final java.util.Map<String, String> SPECIAL_ACCESS = new java.util.HashMap<>();
    static {
        SPECIAL_ACCESS.put("android.app.action.ADD_DEVICE_ADMIN", "裝置管理員");
        SPECIAL_ACCESS.put("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", "所有檔案的存取權");
        SPECIAL_ACCESS.put("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", "所有檔案的存取權");
        SPECIAL_ACCESS.put("android.settings.action.MANAGE_OVERLAY_PERMISSION", "重疊顯示於其他應用程式上方");
        SPECIAL_ACCESS.put("android.settings.action.MANAGE_WRITE_SETTINGS", "修改系統設定");
        SPECIAL_ACCESS.put("android.settings.MANAGE_UNKNOWN_APP_SOURCES", "安裝不明應用程式");
        SPECIAL_ACCESS.put("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", "鬧鐘和提醒");
        SPECIAL_ACCESS.put("android.settings.USAGE_ACCESS_SETTINGS", "應用程式使用資料");
        SPECIAL_ACCESS.put("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", "閱讀、回覆及控制通知");
        SPECIAL_ACCESS.put("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS", "閱讀、回覆及控制通知");
        SPECIAL_ACCESS.put("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS", "「模式」存取權");
        SPECIAL_ACCESS.put("android.settings.REQUEST_MANAGE_MEDIA", "媒體管理應用程式");
        SPECIAL_ACCESS.put("android.settings.PICTURE_IN_PICTURE_SETTINGS", "子母畫面");
        SPECIAL_ACCESS.put("android.settings.REQUEST_MEDIA_ROUTING_CONTROL", "變更媒體輸出");
        SPECIAL_ACCESS.put("android.settings.IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS", "不限制行動數據用量");
        SPECIAL_ACCESS.put("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", "全螢幕通知");
    }

    private ClonePermissionPromptPolicy() { }

    /** Label of the special-access grant this action requests, or null when it is not one. */
    public static String specialAccessLabel(String action) {
        return action == null ? null : SPECIAL_ACCESS.get(action);
    }

    public static boolean isMediaPermission(String permission) {
        return "android.permission.READ_EXTERNAL_STORAGE".equals(permission)
                || "android.permission.READ_MEDIA_IMAGES".equals(permission)
                || "android.permission.READ_MEDIA_VIDEO".equals(permission);
    }

    /** Permissions AppTwin gates per space, in request order, without duplicates. */
    public static List<String> controlled(String[] requested) {
        List<String> result = new ArrayList<>();
        if (requested == null) return result;
        for (String permission : requested) {
            if (permission != null && GuestPermissionPolicy.isControlled(permission)
                    && !result.contains(permission)) result.add(permission);
        }
        return result;
    }

    /** Short labels shown to the user; media permissions collapse into one entry. */
    public static List<String> labels(List<String> permissions) {
        Set<String> labels = new LinkedHashSet<>();
        for (String permission : permissions) labels.add(label(permission));
        return new ArrayList<>(labels);
    }

    static String label(String permission) {
        if (isMediaPermission(permission)) return "照片與影片";
        if ("android.permission.WRITE_EXTERNAL_STORAGE".equals(permission)) return "修改共用儲存空間";
        if ("android.permission.CAMERA".equals(permission)) return "相機";
        if ("android.permission.RECORD_AUDIO".equals(permission)) return "麥克風";
        if (permission.endsWith("_CONTACTS")) return "聯絡人";
        if (permission.contains("LOCATION")) return "位置";
        return permission.substring(permission.lastIndexOf('.') + 1);
    }

    /** True when the host OS grant needed to back this space grant is present. */
    public static boolean hostSatisfied(String permission, HostGrants host) {
        if (isMediaPermission(permission)) {
            return host.granted("android.permission.READ_EXTERNAL_STORAGE") || host.allFiles();
        }
        return host.granted(permission);
    }

    public static boolean allHostSatisfied(List<String> permissions, HostGrants host) {
        for (String permission : permissions) if (!hostSatisfied(permission, host)) return false;
        return true;
    }

    /** Only media grants change what a running native path already resolved at process setup. */
    public static boolean mayNeedRestart(List<String> permissions) {
        for (String permission : permissions) if (isMediaPermission(permission)) return true;
        return false;
    }

    public interface HostGrants {
        boolean granted(String permission);
        boolean allFiles();
    }
}
