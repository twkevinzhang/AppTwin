package com.lody.virtual.client.hook.proxies.am;

import android.os.IBinder;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.Inject;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.Method;

import mirror.android.app.IActivityTaskManager;
import mirror.android.util.Singleton;

/**
 * @author weishu
 * @date 2019-11-05.
 */
@Inject(MethodProxies.class)
public class ActivityTaskManagerStub extends BinderInvocationProxy {
    public ActivityTaskManagerStub() {
        super(IActivityTaskManager.Stub.TYPE, "activity_task");
    }

    @Override
    public void inject() throws Throwable {
        super.inject();
        // ActivityTaskManager caches the Binder interface outside ServiceManager. Replacing only
        // the service cache lets a guest activity bypass StartActivity and launch a real host
        // component (notably Google AccountIntroActivity) after the first lookup.
        Object singleton = mirror.android.app.ActivityTaskManager
                .IActivityTaskManagerSingleton.get();
        Singleton.mInstance.set(singleton, getInvocationStub().getProxyInterface());
        VLog.i("ActivityTaskManagerStub", "cacheHook=%s startHook=%s",
                Singleton.mInstance.get(singleton) == getInvocationStub().getProxyInterface(),
                getInvocationStub().getMethodProxy("startActivity") != null);
    }

    @Override
    public boolean isEnvBad() {
        Object singleton = mirror.android.app.ActivityTaskManager
                .IActivityTaskManagerSingleton.get();
        return Singleton.mInstance.get(singleton) != getInvocationStub().getProxyInterface();
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();

        addMethodProxy(new StaticMethodProxy("activityDestroyed") {
            @Override
            public Object call(Object who, Method method, Object... args) throws Throwable {
                IBinder token = (IBinder) args[0];
                VActivityManager.get().onActivityDestroy(token);
                return super.call(who, method, args);
            }
        });
        addMethodProxy(new StaticMethodProxy("activityResumed") {
            @Override
            public Object call(Object who, Method method, Object... args) throws Throwable {
                IBinder token = (IBinder) args[0];
                VActivityManager.get().onActivityResumed(token);
                return super.call(who, method, args);
            }
        });
        addMethodProxy(new StaticMethodProxy("finishActivity") {
            @Override
            public Object call(Object who, Method method, Object... args) throws Throwable {
                IBinder token = (IBinder) args[0];
                VActivityManager.get().finishActivity(token);
                return super.call(who, method, args);
            }
        });

        if (BuildCompat.isQ()) {
            addMethodProxy(new ReplaceCallingPkgMethodProxy("getAppTasks"));
        }
    }
}
