package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PackagePersistenceVersionTest {
    @Test
    public void v3AndV4MigrateToV5WithAbsentMarker() {
        assertTrue(PackagePersistenceLayer.isVersionMigrationSupported(3, 5));
        assertTrue(PackagePersistenceLayer.isVersionMigrationSupported(4, 5));
        assertFalse(PackagePersistenceLayer.isVersionMigrationSupported(2, 5));
        assertFalse(PackagePersistenceLayer.isVersionMigrationSupported(4, 6));
    }
}
