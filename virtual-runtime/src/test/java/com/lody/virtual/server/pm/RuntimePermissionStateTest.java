package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class RuntimePermissionStateTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void grantsAreIsolatedByUserPackageAndPermissionAndSurviveRestart() throws Exception {
        File file = temporaryFolder.newFile("runtime-permissions.bin");
        assertTrue(file.delete());
        RuntimePermissionState state = new RuntimePermissionState(file);

        state.setGranted(3, "com.example.chat", "android.permission.CAMERA", true);

        assertTrue(state.isGranted(3, "com.example.chat", "android.permission.CAMERA"));
        assertFalse(state.isGranted(4, "com.example.chat", "android.permission.CAMERA"));
        assertFalse(state.isGranted(3, "com.example.mail", "android.permission.CAMERA"));
        assertFalse(state.isGranted(3, "com.example.chat", "android.permission.RECORD_AUDIO"));
        assertTrue(new RuntimePermissionState(file)
                .isGranted(3, "com.example.chat", "android.permission.CAMERA"));
    }

    @Test
    public void revokeAndPackageCleanupDoNotAffectOtherSpaces() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "runtime-permissions.bin");
        RuntimePermissionState state = new RuntimePermissionState(file);
        state.setGranted(3, "com.example.chat", "android.permission.CAMERA", true);
        state.setGranted(4, "com.example.chat", "android.permission.CAMERA", true);
        state.setGranted(3, "com.example.chat", "android.permission.RECORD_AUDIO", true);

        state.setGranted(3, "com.example.chat", "android.permission.CAMERA", false);
        assertFalse(state.isGranted(3, "com.example.chat", "android.permission.CAMERA"));
        assertTrue(state.isGranted(3, "com.example.chat", "android.permission.RECORD_AUDIO"));
        assertTrue(state.isGranted(4, "com.example.chat", "android.permission.CAMERA"));

        state.clearPackageUser(3, "com.example.chat");
        assertFalse(state.isGranted(3, "com.example.chat", "android.permission.RECORD_AUDIO"));
        assertTrue(state.isGranted(4, "com.example.chat", "android.permission.CAMERA"));
    }

    @Test
    public void corruptFileFailsClosed() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "runtime-permissions.bin");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{1, 2, 3, 4});
        }

        RuntimePermissionState state = new RuntimePermissionState(file);

        assertTrue(state.isDamaged());
        assertFalse(state.isGranted(3, "com.example.chat", "android.permission.CAMERA"));
    }

    @Test
    public void failedDurableWriteDoesNotBroadenInMemoryAccess() throws Exception {
        File directoryAtDestination = temporaryFolder.newFolder("runtime-permissions.bin");
        RuntimePermissionState state = new RuntimePermissionState(directoryAtDestination);

        boolean failed = false;
        try {
            state.setGranted(3, "com.example.chat", "android.permission.CAMERA", true);
        } catch (IOException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertFalse(state.isGranted(3, "com.example.chat", "android.permission.CAMERA"));
    }
}
