package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class PackagePendingIntentGenerationStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void bumpIsDurableAndScopedToPackageAndUser() throws Exception {
        File file = new File(temporary.getRoot(), "epochs.bin");
        PackagePendingIntentGenerationStore first =
                new PackagePendingIntentGenerationStore(file);
        long gmsAOld = first.currentOrCreate("com.google.android.gms", 3);
        long otherA = first.currentOrCreate("com.example.other", 3);
        long gmsB = first.currentOrCreate("com.google.android.gms", 4);

        long gmsANew = first.bump("com.google.android.gms", 3);
        PackagePendingIntentGenerationStore restarted =
                new PackagePendingIntentGenerationStore(file);

        assertNotEquals(gmsAOld, gmsANew);
        assertEquals(gmsANew, restarted.currentOrCreate("com.google.android.gms", 3));
        assertEquals(otherA, restarted.currentOrCreate("com.example.other", 3));
        assertEquals(gmsB, restarted.currentOrCreate("com.google.android.gms", 4));
    }
}
