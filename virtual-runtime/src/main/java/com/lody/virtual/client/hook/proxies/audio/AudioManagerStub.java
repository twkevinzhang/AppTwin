package com.lody.virtual.client.hook.proxies.audio;

import android.content.Context;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.fixer.GuestAudioIdentityDiagnostics;

import java.lang.reflect.Method;

import mirror.android.media.IAudioService;

/**
 * @author Lody
 *
 * @see android.media.AudioManager
 */

public class AudioManagerStub extends BinderInvocationProxy {
	public AudioManagerStub() {
		super(IAudioService.Stub.asInterface, Context.AUDIO_SERVICE);
	}

	@Override
	protected void onBindMethods() {
		super.onBindMethods();
		addMethodProxy(new MethodProxy() {
			@Override
			public String getMethodName() {
				return "trackPlayer";
			}

			@Override
			public Object call(Object who, Method method, Object... args) throws Throwable {
				int sample = GuestAudioIdentityDiagnostics.beforeTrackPlayer(args);
				Object result = method.invoke(who, args);
				GuestAudioIdentityDiagnostics.afterTrackPlayer(sample, result);
				return result;
			}
		});
		addMethodProxy(new ReplaceLastPkgMethodProxy("adjustVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("adjustLocalOrRemoteStreamVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("adjustSuggestedStreamVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("adjustStreamVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("adjustMasterVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setStreamVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setMasterVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setMicrophoneMute"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setRingerModeExternal"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setRingerModeInternal"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setMode"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("avrcpSupportsAbsoluteVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("abandonAudioFocus"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("requestAudioFocus"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setWiredDeviceConnectionState"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setSpeakerphoneOn"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("setBluetoothScoOn"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("stopBluetoothSco"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("startBluetoothSco"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("disableSafeMediaVolume"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("registerRemoteControlClient"));
		addMethodProxy(new ReplaceLastPkgMethodProxy("unregisterAudioFocusClient"));
	}
}
