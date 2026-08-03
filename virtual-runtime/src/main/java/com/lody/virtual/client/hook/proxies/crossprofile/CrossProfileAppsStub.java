package com.lody.virtual.client.hook.proxies.crossprofile;

import android.content.Context;
import android.content.pm.CrossProfileApps;
import android.os.IInterface;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;

import java.util.Collections;

/** Keeps guest apps from querying or crossing Android OS profiles. */
public final class CrossProfileAppsStub extends BinderInvocationProxy {

    public CrossProfileAppsStub() {
        super(getInterface(), Context.CROSS_PROFILE_APPS_SERVICE);
    }

    private static IInterface getInterface() {
        CrossProfileApps service = (CrossProfileApps) VirtualCore.get().getContext()
                .getSystemService(Context.CROSS_PROFILE_APPS_SERVICE);
        return mirror.android.content.pm.CrossProfileApps.mService.get(service);
    }

    @Override
    public void inject() throws Throwable {
        super.inject();
        CrossProfileApps service = (CrossProfileApps) VirtualCore.get().getContext()
                .getSystemService(Context.CROSS_PROFILE_APPS_SERVICE);
        mirror.android.content.pm.CrossProfileApps.mService.set(
                service, getInvocationStub().getProxyInterface());
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ResultStaticMethodProxy(
                "getTargetUserProfiles", Collections.emptyList()));
        addMethodProxy(new ResultStaticMethodProxy("canInteractAcrossProfiles", false));
        addMethodProxy(new ResultStaticMethodProxy("canRequestInteractAcrossProfiles", false));
        addMethodProxy(new ResultStaticMethodProxy("canConfigureInteractAcrossProfiles", false));
    }
}
