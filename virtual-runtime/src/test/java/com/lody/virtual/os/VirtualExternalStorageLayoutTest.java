package com.lody.virtual.os;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;

public class VirtualExternalStorageLayoutTest {

    @Test
    public void keepsSharedAndPrivateGuestStorageInsideHostExternalDirectory() {
        File externalRoot = new File("/storage/emulated/0");

        assertEquals(
                "/storage/emulated/0/Android/data/org.maskaccounts/virtual/vsdcard/7",
                VirtualExternalStorageLayout.sharedStorageForUser(
                        externalRoot, "org.maskaccounts", 7).getPath());
        assertEquals(
                "/storage/emulated/0/Android/data/org.maskaccounts/virtual/7",
                VirtualExternalStorageLayout.privateStorageForUser(
                        externalRoot, "org.maskaccounts", 7).getPath());
    }
}
