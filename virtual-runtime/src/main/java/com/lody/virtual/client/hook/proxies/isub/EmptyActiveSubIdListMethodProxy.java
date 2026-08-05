package com.lody.virtual.client.hook.proxies.isub;

import com.lody.virtual.client.hook.base.StaticMethodProxy;

import java.lang.reflect.Method;

/** Prevents a guest from querying the host's privileged physical-SIM subscription ids. */
final class EmptyActiveSubIdListMethodProxy extends StaticMethodProxy {
    EmptyActiveSubIdListMethodProxy() {
        super("getActiveSubIdList");
    }

    @Override
    public Object call(Object who, Method method, Object... args) {
        return new int[0];
    }
}
