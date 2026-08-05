package com.lody.virtual.client.hook.proxies.user;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;

import java.lang.reflect.Method;
import java.util.Collections;

import mirror.android.content.pm.UserInfo;
import mirror.android.os.IUserManager;

/**
 * @author Lody
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
public class UserManagerStub extends BinderInvocationProxy {

    public UserManagerStub() {
        super(IUserManager.Stub.asInterface, Context.USER_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplaceCallingPkgMethodProxy("setApplicationRestrictions"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getApplicationRestrictions"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getApplicationRestrictionsForUser"));
        addMethodProxy(new ResultStaticMethodProxy("getProfileParent", null));
        addMethodProxy(new ResultStaticMethodProxy("getUserIcon", null));
        addMethodProxy(new ResultStaticMethodProxy("getUserInfo", UserInfo.ctor.newInstance(0, "Admin", UserInfo.FLAG_PRIMARY.get())));
        addMethodProxy(new ResultStaticMethodProxy("getDefaultGuestRestrictions", null));
        addMethodProxy(new ResultStaticMethodProxy("setDefaultGuestRestrictions", null));
        addMethodProxy(new ResultStaticMethodProxy("removeRestrictions", null));
        addMethodProxy(new ResultStaticMethodProxy("getUsers", Collections.EMPTY_LIST));
        addMethodProxy(new ResultStaticMethodProxy("createUser", null));
        addMethodProxy(new ResultStaticMethodProxy("createProfileForUser", null));
        addMethodProxy(new ResultStaticMethodProxy("getProfiles", Collections.EMPTY_LIST));
        // A Group is an app-level virtual secondary user, not Android's privileged main user.
        addMethodProxy(new ResultStaticMethodProxy("isMainUser", false));
        // Seed-account APIs are privileged OS-user provisioning state. A virtual user starts with
        // no seed account and must never inherit the host user's setup/account metadata.
        addMethodProxy(new ResultStaticMethodProxy("getSeedAccountName", null));
        addMethodProxy(new ResultStaticMethodProxy("getSeedAccountType", null));
        addMethodProxy(new ResultStaticMethodProxy("getSeedAccountOptions", null));
        addMethodProxy(new ResultStaticMethodProxy("clearSeedAccountData", null));
        addMethodProxy(new ResultStaticMethodProxy("setSeedAccountData", null));
        addMethodProxy(new ResultStaticMethodProxy("someUserHasSeedAccount", false));
        addMethodProxy(new IsUserOfType());
    }

    /**
     * Android 12 protects {@code IUserManager.isUserOfType} with MANAGE_USERS. Guest code runs
     * under the host application's real Android user, so delegating this query both leaks host
     * profile state and crashes callers such as GMS Checkin. A virtual user is modelled as a
     * normal full secondary user; it is never an Android guest, managed profile, clone profile,
     * or other OS-level profile/container.
     */
    static boolean isVirtualUserOfType(String userType) {
        return "android.os.usertype.full.SECONDARY".equals(userType);
    }

    private static final class IsUserOfType extends MethodProxy {
        @Override
        public String getMethodName() {
            return "isUserOfType";
        }

        @Override
        public Object call(Object who, Method method, Object... args) {
            String requestedType = null;
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof String) {
                        requestedType = (String) arg;
                    }
                }
            }
            return isVirtualUserOfType(requestedType);
        }
    }
}
