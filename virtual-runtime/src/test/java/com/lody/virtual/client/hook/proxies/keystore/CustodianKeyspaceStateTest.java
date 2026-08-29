package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CustodianKeyspaceStateTest {
    private static final String OWNER = "34480577-4e1a-449f-bc5f-b5cbc02e8a7c";
    private static final String KEYSPACE = "7fba3b64-10ac-4541-b7e4-14706040e272";
    private static final String LINE = "jp.naver.line.android";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingStateMeansLegacySpace() throws Exception {
        assertNull(CustodianKeyspaceState.read(new File(temporaryFolder.newFolder(), "missing")));
    }

    @Test
    public void stateRoundTripsCanonicalOwnerAndKeyspace() throws Exception {
        File file = new File(temporaryFolder.newFolder(), "state.properties");
        CustodianKeyspaceState.write(file, new CustodianKeyspaceState.Record(OWNER, KEYSPACE));
        CustodianKeyspaceState.Record restored = CustodianKeyspaceState.read(file);
        assertEquals(OWNER, restored.ownerSpaceId);
        assertEquals(KEYSPACE, restored.keyspaceId);
    }

    @Test
    public void damagedStateFailsClosed() throws Exception {
        File file = temporaryFolder.newFile();
        java.nio.file.Files.write(file.toPath(), "schemaVersion=1\nownerSpaceId=bad\n".getBytes());
        assertThrows(IllegalStateException.class, () -> CustodianKeyspaceState.read(file));
    }

    @Test
    public void retainedKeyspaceMarkerRoundTripsAndMatchesExactly() throws Exception {
        File file = new File(temporaryFolder.newFolder(), "retained.properties");
        CustodianKeyspaceState.writeRetained(
                file, new CustodianKeyspaceState.RetainedRecord(LINE, KEYSPACE));

        CustodianKeyspaceState.RetainedRecord restored =
                CustodianKeyspaceState.readRetained(file);
        assertEquals(LINE, restored.packageName);
        assertEquals(KEYSPACE, restored.keyspaceId);
    }

    @Test
    public void missingRetainedMarkerMeansNoReservation() throws Exception {
        assertNull(CustodianKeyspaceState.readRetained(
                new File(temporaryFolder.newFolder(), "missing")));
    }

    @Test
    public void malformedRetainedMarkerFailsClosed() throws Exception {
        File file = temporaryFolder.newFile();
        java.nio.file.Files.write(
                file.toPath(),
                ("schemaVersion=1\npackageName=" + LINE + "\nkeyspaceId=bad\n").getBytes());
        assertThrows(
                IllegalStateException.class, () -> CustodianKeyspaceState.readRetained(file));
    }
}
