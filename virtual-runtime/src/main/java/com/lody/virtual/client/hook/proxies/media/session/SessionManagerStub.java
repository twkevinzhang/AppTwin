package com.lody.virtual.client.hook.proxies.media.session;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;

import java.lang.reflect.Method;

import mirror.android.media.session.ISessionManager;

/**
 * @author Lody
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class SessionManagerStub extends BinderInvocationProxy {

	public SessionManagerStub() {
		super(ISessionManager.Stub.asInterface, Context.MEDIA_SESSION_SERVICE);
	}

	@Override
	protected void onBindMethods() {
		super.onBindMethods();
		addMethodProxy(new ReplaceCallingPkgMethodProxy("createSession"));
		addMethodProxy(new StaticMethodProxy(MediaKeyCallerIdentityPolicy.METHOD) {
			@Override
			public boolean beforeCall(Object who, Method method, Object... args) {
				Class<?>[] parameterTypes = method.getParameterTypes();
				String[] names = new String[parameterTypes.length];
				for (int i = 0; i < parameterTypes.length; i++) {
					names[i] = parameterTypes[i].getName();
				}
				MediaKeyCallerIdentityPolicy.rewriteCaller(method.getName(),
						method.getReturnType().getName(), names, args, getAppPkg(), getHostPkg());
				return super.beforeCall(who, method, args);
			}
		});
	}
}
