package com.lody.virtual.helper.compat;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.SystemClock;

import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;

import java.io.File;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import mirror.com.android.internal.content.NativeLibraryHelper;
import mirror.dalvik.system.VMRuntime;

public class NativeLibraryHelperCompat {

	private static String TAG = NativeLibraryHelperCompat.class.getSimpleName();

	public static int copyNativeBinaries(File apkFile, File sharedLibraryDir) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			return copyNativeBinariesAfterL(apkFile, sharedLibraryDir);
		} else {
			return copyNativeBinariesBeforeL(apkFile, sharedLibraryDir);
		}
	}

	private static int copyNativeBinariesBeforeL(File apkFile, File sharedLibraryDir) {
		try {
			return Reflect.on(NativeLibraryHelper.TYPE).call("copyNativeBinariesIfNeededLI", apkFile, sharedLibraryDir)
					.get();
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return -1;
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private static int copyNativeBinariesAfterL(File apkFile, File sharedLibraryDir) {
		Set<String> abiSet = getABIsFromApk(apkFile.getAbsolutePath());
		if (abiSet == null) {
			return -1;
		}
		if (abiSet.isEmpty()) {
			return 0;
		}
		String abi = selectRuntimeAbi(abiSet);
		if (abi == null) {
			VLog.e(TAG, "Not match any abi [%s].", apkFile.getPath());
			return -1;
		}

		try {
			Object handle = NativeLibraryHelper.Handle.create.call(apkFile);
			if (handle != null) {
				NativeLibraryHelper.copyNativeBinaries.call(handle, sharedLibraryDir, abi);
			}
		} catch (Throwable e) {
			VLog.w(TAG, "platform native extraction failed for " + apkFile, e);
		}
		try {
			int extracted = NativeLibraryExtractor.extractMissing(apkFile, sharedLibraryDir, abi);
			if (extracted > 0) {
				VLog.i(TAG, "native extraction fallback apk=%s abi=%s extracted=%d",
						apkFile.getName(), abi, extracted);
			}
			return 1;
		} catch (Throwable e) {
			VLog.e(TAG, "native extraction fallback failed for " + apkFile, e);
			return -1;
		}
	}

	private static String selectRuntimeAbi(Set<String> supportedAbis) {
		boolean is64Bit = VMRuntime.is64Bit.call(VMRuntime.getRuntime.call());
		String[] runtimeAbis = is64Bit
				? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
		for (String abi : runtimeAbis) {
			if (supportedAbis.contains(abi)) {
				return abi;
			}
		}
		return null;
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private static boolean isVM64(Set<String> supportedABIs) {
		if (Build.SUPPORTED_64_BIT_ABIS.length == 0) {
			return false;
		}

		if (supportedABIs == null || supportedABIs.isEmpty()) {
			return true;
		}

		for (String supportedAbi : supportedABIs) {
			if ("arm64-v8a".endsWith(supportedAbi) || "x86_64".equals(supportedAbi) || "mips64".equals(supportedAbi)) {
				return true;
			}
		}

		return false;
	}

	private static Set<String> getABIsFromApk(String apk) {
		try (ZipFile apkFile = new ZipFile(apk)) {
			Enumeration<? extends ZipEntry> entries = apkFile.entries();
			Set<String> supportedABIs = new HashSet<String>();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.contains("../")) {
					continue;
				}
				if (name.startsWith("lib/") && !entry.isDirectory() && name.endsWith(".so")) {
					String supportedAbi = name.substring(name.indexOf("/") + 1, name.lastIndexOf("/"));
					supportedABIs.add(supportedAbi);
				}
			}
			return supportedABIs;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public static boolean isApk64(String apk) {
		long start = SystemClock.elapsedRealtime();
		Set<String> abIsFromApk = getABIsFromApk(apk);
		System.out.println("pkg: " + apk + " abis: " + abIsFromApk + " cost: " + (SystemClock.elapsedRealtime() - start));
		return isVM64(abIsFromApk);
	}
}
