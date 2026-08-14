package com.lody.virtual.client.hook.proxies.telecom;

import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.fixer.ContextFixer;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.StaticMethodProxy;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;

import java.lang.reflect.Method;

import mirror.com.android.internal.telecom.ITelecomService;

/** Telecom binder bridge for LINE's self-managed incoming calls. */
public final class TelecomManagerStub extends BinderInvocationProxy {
    private static final String TAG = TelecomManagerStub.class.getSimpleName();
    private static final String TELECOM_SERVICE = "telecom";

    public TelecomManagerStub() {
        super(ITelecomService.Stub.asInterface, TELECOM_SERVICE);
    }

    TelecomManagerStub(BinderInvocationStub invocationStub) {
        super(invocationStub, TELECOM_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new RewritePhoneAccountArgs("registerPhoneAccount"));
        addMethodProxy(new RewritePhoneAccountArgs("unregisterPhoneAccount"));
        addMethodProxy(new RewritePhoneAccountArgs("addNewIncomingCall"));
        addMethodProxy(new RewritePhoneAccountArgs("addNewUnknownCall"));
    }

    private static final class RewritePhoneAccountArgs extends StaticMethodProxy {
        RewritePhoneAccountArgs(String name) {
            super(name);
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            String currentPackage = VClientImpl.get().getCurrentPackage();
            String hostPackage = VirtualCore.get().getHostPkg();
            int virtualUserId = VUserHandle.getUserId(VClientImpl.get().getVUid());
            int slot = TelecomPhoneAccountPolicy.parseStubSlot(
                    hostPackage, VirtualCore.get().getProcessName());
            for (int index = 0; args != null && index < args.length; index++) {
                Object argument = args[index];
                if (argument instanceof PhoneAccount) {
                    Object rewritten = TelecomPhoneAccountCompat.rewriteAccount(
                            (PhoneAccount) argument, currentPackage, hostPackage,
                            virtualUserId, slot);
                    args[index] = rewritten;
                    logRewrite(method, argument, rewritten, virtualUserId, slot);
                } else if (argument instanceof PhoneAccountHandle) {
                    Object rewritten = TelecomPhoneAccountCompat.rewriteHandle(
                            (PhoneAccountHandle) argument, currentPackage, hostPackage,
                            virtualUserId, slot);
                    args[index] = rewritten;
                    logRewrite(method, argument, rewritten, virtualUserId, slot);
                } else if (argument instanceof String && currentPackage != null
                        && currentPackage.equals(argument)) {
                    args[index] = hostPackage;
                } else if (argument != null && "android.content.AttributionSource".equals(
                        argument.getClass().getName())) {
                    ContextFixer.fixAttributionSource(
                            argument, hostPackage, VirtualCore.get().myUid());
                }
            }
            return super.beforeCall(who, method, args);
        }

        private static void logRewrite(Method method, Object original, Object rewritten,
                                       int virtualUserId, int slot) {
            if (original != rewritten) {
                VLog.i(TAG, "Rewrote LINE Telecom identity method=" + method.getName()
                        + " user=" + virtualUserId + " slot=" + slot);
            }
        }
    }
}
