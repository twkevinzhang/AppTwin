package com.lody.virtual.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.io.File;

public class VirtualExternalStorageLayoutTest {

    @Test
    public void keepsSharedAndPrivateGuestStorageInsideHostExternalFilesDirectory() {
        File hostExternalFilesDir =
                new File("/storage/emulated/0/Android/data/org.apptwin/files");

        assertEquals(
                "/storage/emulated/0/Android/data/org.apptwin/files/virtual/vsdcard",
                VirtualExternalStorageLayout.sharedStorageBase(hostExternalFilesDir).getPath());
        assertEquals(
                "/storage/emulated/0/Android/data/org.apptwin/files/virtual/vsdcard/7",
                VirtualExternalStorageLayout.sharedStorageForUser(
                        hostExternalFilesDir, 7).getPath());
        assertEquals(
                "/storage/emulated/0/Android/data/org.apptwin/files/virtual/7",
                VirtualExternalStorageLayout.privateStorageForUser(
                        hostExternalFilesDir, 7).getPath());
        assertNotEquals(
                VirtualExternalStorageLayout.sharedStorageForUser(hostExternalFilesDir, 7),
                VirtualExternalStorageLayout.sharedStorageForUser(hostExternalFilesDir, 8));
        assertNotEquals(
                VirtualExternalStorageLayout.sharedStorageForUser(hostExternalFilesDir, 7),
                VirtualExternalStorageLayout.privateStorageForUser(hostExternalFilesDir, 7));
    }
}
