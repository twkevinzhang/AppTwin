package com.lody.virtual.helper.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfoList;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class ParceledListSliceCompatTest {
    interface Api37PackageManager {
        PackageInfoList getInstalledPackages(long flags, int userId);
    }

    @Test
    public void createsApi37PackageInfoListForInstalledPackagesReturnType() throws Exception {
        Method method = Api37PackageManager.class.getMethod(
                "getInstalledPackages", long.class, int.class);
        List<Object> packages = new ArrayList<>();
        packages.add(new Object());

        assertTrue(ParceledListSliceCompat.isReturnListContainer(method));
        Object result = ParceledListSliceCompat.createForReturnType(method, packages);

        assertTrue(result instanceof PackageInfoList);
        assertSame(packages, ((PackageInfoList) result).getList());
        assertEquals(1, ((PackageInfoList) result).getList().size());
    }
}
