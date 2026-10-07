package com.lody.virtual.helper.compat;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VPackageManager;
import org.xmlpull.v1.XmlPullParser;

/** Exact-user manifest inventory, shared by server authorization and the guest-local bridge. */
public final class SyncAdapterRegistration {
    private static final String NS = "http://schemas.android.com/apk/res/android";
    private SyncAdapterRegistration() {}
    public static boolean declaredLocally(Resources resources, ServiceInfo service, ComponentName component) {
        if (resources == null || service == null || component == null || service.metaData == null
                || !component.getPackageName().equals(service.packageName) || !component.getClassName().equals(service.name)) return false;
        int id = service.metaData.getInt("android.content.SyncAdapter", 0);
        if (id == 0) return false;
        try (XmlResourceParser parser = resources.getXml(id)) {
            while (parser.getEventType() != XmlPullParser.START_TAG && parser.getEventType() != XmlPullParser.END_DOCUMENT) parser.next();
            String type = value(parser, resources, "accountType"), authority = value(parser, resources, "contentAuthority");
            return "sync-adapter".equals(parser.getName()) && type != null && !type.isEmpty() && authority != null && !authority.isEmpty();
        } catch (Exception ignored) { return false; }
    }
    public static boolean matches(int user, ComponentName component, String type, String authority) {
        if (user <= 0 || component == null) return false;
        for (ResolveInfo info : VPackageManager.get().queryIntentServices(
                new Intent("android.content.SyncAdapter").setComponent(component), null,
                PackageManager.GET_META_DATA, user)) {
            if (info.serviceInfo == null || info.serviceInfo.metaData == null) continue;
            int id = info.serviceInfo.metaData.getInt("android.content.SyncAdapter", 0);
            if (id == 0) continue;
            try {
                Resources resources = VirtualCore.get().getResources(component.getPackageName());
                try (XmlResourceParser parser = resources.getXml(id)) {
                    while (parser.getEventType() != XmlPullParser.START_TAG && parser.getEventType() != XmlPullParser.END_DOCUMENT) parser.next();
                    if (!"sync-adapter".equals(parser.getName())) continue;
                    String actualType = value(parser, resources, "accountType");
                    String actualAuthority = value(parser, resources, "contentAuthority");
                    if (actualType == null || actualType.isEmpty() || actualAuthority == null || actualAuthority.isEmpty()) continue;
                    if ((type == null || type.equals(actualType)) && (authority == null || authority.equals(actualAuthority))) return true;
                }
            } catch (Exception ignored) { return false; }
        }
        return false;
    }
    private static String value(XmlResourceParser parser, Resources resources, String name) {
        int id = parser.getAttributeResourceValue(NS, name, 0);
        return id == 0 ? parser.getAttributeValue(NS, name) : resources.getString(id);
    }
}
