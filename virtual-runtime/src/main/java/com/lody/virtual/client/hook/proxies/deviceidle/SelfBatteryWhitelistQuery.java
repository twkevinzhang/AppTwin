package com.lody.virtual.client.hook.proxies.deviceidle;

import com.lody.virtual.client.hook.base.MethodProxy;

import java.lang.reflect.Method;

/** Only self queries share the host's exemption; other packages retain their own state. */
class SelfBatteryWhitelistQuery extends MethodProxy {
    private final String methodName;

    SelfBatteryWhitelistQuery(String methodName) {
        this.methodName = methodName;
    }

    @Override
    public String getMethodName() {
        return methodName;
    }

    protected String currentPackage() {
        return getAppPkg();
    }

    protected String hostPackage() {
        return getHostPkg();
    }

    @Override
    public Object call(Object who, Method method, Object... args) throws Throwable {
        if (args != null && args.length == 1 && args[0] instanceof String) {
            String guest = currentPackage();
            String host = hostPackage();
            if (guest != null && guest.equals(args[0]) && host != null) {
                args[0] = host;
            }
        }
        return super.call(who, method, args);
    }
}
