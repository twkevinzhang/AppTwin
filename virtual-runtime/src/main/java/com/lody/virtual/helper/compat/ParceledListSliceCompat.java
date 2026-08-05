package com.lody.virtual.helper.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import mirror.android.content.pm.ParceledListSlice;
import mirror.android.content.pm.ParceledListSliceJBMR2;

/**
 * @author Lody
 *
 */
public class ParceledListSliceCompat {
	private static final String PACKAGE_INFO_LIST = "android.content.pm.PackageInfoList";

	public static boolean isReturnParceledListSlice(Method method) {
		if (method == null) {
			return false;
		}
		Class<?> returnType = method.getReturnType();
		return returnType == ParceledListSlice.TYPE
				|| "android.content.pm.ParceledListSlice".equals(returnType.getName());
	}

	public static boolean isReturnListContainer(Method method) {
		return isReturnParceledListSlice(method) || isReturnPackageInfoList(method);
	}

	public static Object createForReturnType(Method method, List list) {
		if (!isReturnPackageInfoList(method)) {
			return create(list);
		}
		try {
			Constructor<?> constructor = method.getReturnType().getConstructor(List.class);
			return constructor.newInstance(list);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to create " + PACKAGE_INFO_LIST, e);
		}
	}

	private static boolean isReturnPackageInfoList(Method method) {
		return method != null && PACKAGE_INFO_LIST.equals(method.getReturnType().getName());
	}

	public static  Object create(List list) {
		if (ParceledListSliceJBMR2.ctor != null) {
			return ParceledListSliceJBMR2.ctor.newInstance(list);
		} else {
			Object slice = ParceledListSlice.ctor.newInstance();
			for (Object item : list) {
				ParceledListSlice.append.call(slice, item);
			}
			ParceledListSlice.setLastSlice.call(slice, true);
			return slice;
		}
	}

	public static List getList(Object parceledList) {
		if (parceledList == null || ParceledListSlice.TYPE == null
				|| !ParceledListSlice.TYPE.isInstance(parceledList)) {
			return Collections.EMPTY_LIST;
		}

		if (ParceledListSliceJBMR2.getList != null) {
			return ParceledListSliceJBMR2.getList.call(parceledList);
		} else {
			return ParceledListSlice.getList.call(parceledList);
		}
	}
}
