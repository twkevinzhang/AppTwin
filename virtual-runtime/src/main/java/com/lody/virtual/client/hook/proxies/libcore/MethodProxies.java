package com.lody.virtual.client.hook.proxies.libcore;

import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;

import mirror.libcore.io.Os;

/**
 * @author Lody
 */

class MethodProxies {

    static String redirectStatPath(String path, Function<String, String> redirector) {
        return path == null ? null : redirector.apply(path);
    }

    static int reportedStatUid(int ownerUid, int hostUid, int guestFacingUid) {
        return ownerUid == hostUid ? guestFacingUid : ownerUid;
    }

    static class Lstat extends Stat {

        @Override
        public String getMethodName() {
            return "lstat";
        }
    }

    static class Getpwnam extends MethodProxy {
            @Override
            public String getMethodName() {
                return "getpwnam";
            }

            @Override
            public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
                if (result != null) {
                    Reflect pwd = Reflect.on(result);
                    int uid = pwd.get("pw_uid");
                    if (uid == VirtualCore.get().myUid()) {
                        pwd.set("pw_uid", VClientImpl.get().getVUid());
                    }
                }
                return result;
            }
        }

    static class GetUid extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getuid";
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            int uid = (int) result;
            return NativeEngine.onGetUid(uid);
        }
    }

    static class GetsockoptUcred extends MethodProxy {
            @Override
            public String getMethodName() {
                return "getsockoptUcred";
            }

            @Override
            public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
                if (result != null) {
                    Reflect ucred = Reflect.on(result);
                    int uid = ucred.get("uid");
                    if (uid == VirtualCore.get().myUid()) {
                        ucred.set("uid", getBaseVUid());
                    }
                }
                return result;
            }
        }

    static class Stat extends MethodProxy {

        private static Field st_uid;

        static {
            try {
                Method stat = Os.TYPE.getMethod("stat", String.class);
                Class<?> StructStat = stat.getReturnType();
                st_uid = StructStat.getDeclaredField("st_uid");
                st_uid.setAccessible(true);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            if (args != null && args.length > 0 && args[0] instanceof String) {
                // libcore.io.Os bypasses the libc PLT hooks used by IOUniformer. Rewrite the
                // pathname here as well so DexFile's optimized-directory ownership check and
                // other Java-level stat callers inspect the isolated guest directory.
                args[0] = redirectStatPath((String) args[0], NativeEngine::getRedirectedPath);
            }
            return super.beforeCall(who, method, args);
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            int uid = (int) st_uid.get(result);
            st_uid.set(result, reportedStatUid(
                    uid,
                    VirtualCore.get().myUid(),
                    VClientImpl.get().getBaseReportedUid(uid)));
            return result;
        }

        @Override
        public String getMethodName() {
            return "stat";
        }
    }
}
