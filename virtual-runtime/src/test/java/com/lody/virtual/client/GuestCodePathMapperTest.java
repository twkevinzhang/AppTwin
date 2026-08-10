package com.lody.virtual.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class GuestCodePathMapperTest {

    @Test
    public void create_exposesPackageOwnedPathsWithoutHostPath() {
        GuestCodePathMapper.Mapping mapping = GuestCodePathMapper.create(
                "com.shopee.tw",
                "/data/user/0/org.apptwin/virtual/data/app/com.shopee.tw/base.apk",
                new String[]{
                        "/data/user/0/org.apptwin/virtual/data/app/com.shopee.tw/split_config.apk",
                });

        assertEquals("/data/app/com.shopee.tw/base.apk", mapping.guestBasePath);
        assertArrayEquals(
                new String[]{"/data/app/com.shopee.tw/split_0.apk"},
                mapping.guestSplitPaths);
        assertFalse(mapping.guestBasePath.contains("org.apptwin"));
        assertFalse(mapping.guestSplitPaths[0].contains("org.apptwin"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_rejectsMissingPhysicalBase() {
        GuestCodePathMapper.create("com.shopee.tw", "", null);
    }
}
