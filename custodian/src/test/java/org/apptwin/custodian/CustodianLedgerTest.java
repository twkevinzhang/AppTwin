package org.apptwin.custodian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CustodianLedgerTest {
    private static final String SOURCE = "34480577-4e1a-449f-bc5f-b5cbc02e8a7c";
    private static final String DESTINATION = "7fba3b64-10ac-4541-b7e4-14706040e272";
    private static final String OTHER_DESTINATION = "efdb31df-00b8-42ce-b99f-ad7c6159b914";
    private static final String ARCHIVE = "519042cc-8dd9-4aa6-981b-57e5a9f28ce1";
    private static final String REPLACEMENT_ARCHIVE =
            "d4c183cb-2015-4924-a22d-b9d6a38b7d12";
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_DIGEST =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String LINE = "jp.naver.line.android";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void registerIsIdempotentAndDurable() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        assertEquals(SOURCE, ledger.register(SOURCE, LINE));
        assertEquals(SOURCE, ledger.register(SOURCE, LINE));
        assertEquals(SOURCE, ledger.resolve(SOURCE, LINE));
    }

    @Test
    public void transferMovesExclusiveOwnership() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        String keyspace = ledger.register(SOURCE, LINE);
        assertTrue(ledger.transfer(SOURCE, DESTINATION, LINE, keyspace));
        assertNull(ledger.resolve(SOURCE, LINE));
        assertEquals(keyspace, ledger.resolve(DESTINATION, LINE));
        assertFalse(ledger.transfer(SOURCE, DESTINATION, LINE, keyspace));
    }

    @Test
    public void releaseRequiresExactCurrentOwner() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        String keyspace = ledger.register(SOURCE, LINE);
        assertFalse(ledger.release(DESTINATION, LINE, keyspace));
        assertTrue(ledger.release(SOURCE, LINE, keyspace));
        assertNull(ledger.resolve(SOURCE, LINE));
    }

    @Test
    public void sealedArchiveReservationIsIdempotentAndDurable() throws Exception {
        java.io.File filesDir = temporaryFolder.newFolder();
        CustodianLedger ledger = new CustodianLedger(filesDir);
        String keyspace = ledger.register(SOURCE, LINE);

        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST));
        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST));
        assertFalse(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, OTHER_DIGEST));
        assertEquals(SOURCE, ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
        assertNull(ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, OTHER_DIGEST));

        CustodianLedger reopened = new CustodianLedger(filesDir);
        assertEquals(SOURCE, reopened.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
        assertFalse(reopened.release(SOURCE, LINE, keyspace));
        assertFalse(reopened.transfer(SOURCE, DESTINATION, LINE, keyspace));
    }

    @Test
    public void currentOwnerCanAtomicallyReplaceArchiveReservation() throws Exception {
        java.io.File filesDir = temporaryFolder.newFolder();
        CustodianLedger ledger = new CustodianLedger(filesDir);
        String keyspace = ledger.register(SOURCE, LINE);
        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST));

        assertTrue(ledger.sealArchive(
                SOURCE, REPLACEMENT_ARCHIVE, LINE, keyspace, OTHER_DIGEST));
        assertNull(ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
        assertEquals(
                SOURCE,
                ledger.resolveArchivedOwner(
                        REPLACEMENT_ARCHIVE, LINE, keyspace, OTHER_DIGEST));

        CustodianLedger reopened = new CustodianLedger(filesDir);
        assertNull(reopened.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
        assertEquals(
                SOURCE,
                reopened.resolveArchivedOwner(
                        REPLACEMENT_ARCHIVE, LINE, keyspace, OTHER_DIGEST));
    }

    @Test
    public void sameArchiveIdWithDifferentDigestIsRejectedWithoutChangingReservation()
            throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        String keyspace = ledger.register(SOURCE, LINE);
        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST));

        assertFalse(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, OTHER_DIGEST));
        assertEquals(SOURCE, ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
        assertNull(ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, OTHER_DIGEST));
    }

    @Test
    public void wrongOwnerCannotReplaceArchiveReservation() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        String keyspace = ledger.register(SOURCE, LINE);
        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST));

        assertFalse(ledger.sealArchive(
                DESTINATION, REPLACEMENT_ARCHIVE, LINE, keyspace, OTHER_DIGEST));
        assertEquals(SOURCE, ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
        assertNull(ledger.resolveArchivedOwner(
                REPLACEMENT_ARCHIVE, LINE, keyspace, OTHER_DIGEST));
    }

    @Test
    public void replacingArchiveClearsPreviousOwnerAndInvalidatesOldClaim() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        String keyspace = ledger.register(SOURCE, LINE);
        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST));
        assertTrue(ledger.claimArchive(
                ARCHIVE, SOURCE, DESTINATION, LINE, keyspace, DIGEST));

        assertTrue(ledger.sealArchive(
                DESTINATION, REPLACEMENT_ARCHIVE, LINE, keyspace, OTHER_DIGEST));
        assertFalse(ledger.sealArchive(
                SOURCE, ARCHIVE, LINE, keyspace, DIGEST));
        assertNull(ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
        assertEquals(
                DESTINATION,
                ledger.resolveArchivedOwner(
                        REPLACEMENT_ARCHIVE, LINE, keyspace, OTHER_DIGEST));
        assertFalse(ledger.claimArchive(
                ARCHIVE, SOURCE, OTHER_DESTINATION, LINE, keyspace, DIGEST));
        assertTrue(ledger.claimArchive(
                REPLACEMENT_ARCHIVE,
                DESTINATION,
                OTHER_DESTINATION,
                LINE,
                keyspace,
                OTHER_DIGEST));
        assertFalse(ledger.claimArchive(
                REPLACEMENT_ARCHIVE,
                SOURCE,
                DESTINATION,
                LINE,
                keyspace,
                OTHER_DIGEST));
        assertEquals(
                OTHER_DESTINATION,
                ledger.resolveArchivedOwner(
                        REPLACEMENT_ARCHIVE, LINE, keyspace, OTHER_DIGEST));
    }

    @Test
    public void claimArchiveAtomicallyMovesExclusiveOwnershipAndCanBeRetried() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        String keyspace = ledger.register(SOURCE, LINE);
        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST));

        assertTrue(ledger.claimArchive(
                ARCHIVE, SOURCE, DESTINATION, LINE, keyspace, DIGEST));
        assertTrue(ledger.claimArchive(
                ARCHIVE, SOURCE, DESTINATION, LINE, keyspace, DIGEST));
        assertNull(ledger.resolve(SOURCE, LINE));
        assertEquals(keyspace, ledger.resolve(DESTINATION, LINE));
        assertEquals(
                DESTINATION,
                ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));

        assertFalse(ledger.claimArchive(
                ARCHIVE, SOURCE, OTHER_DESTINATION, LINE, keyspace, DIGEST));
        assertTrue(ledger.claimArchive(
                ARCHIVE, DESTINATION, OTHER_DESTINATION, LINE, keyspace, DIGEST));
        assertEquals(
                OTHER_DESTINATION,
                ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
    }

    @Test
    public void claimRejectsWrongDigestOwnerAndOccupiedDestination() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        String keyspace = ledger.register(SOURCE, LINE);
        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST));
        ledger.register(DESTINATION, LINE);

        assertFalse(ledger.claimArchive(
                ARCHIVE, SOURCE, OTHER_DESTINATION, LINE, keyspace, OTHER_DIGEST));
        assertFalse(ledger.claimArchive(
                ARCHIVE, OTHER_DESTINATION, DESTINATION, LINE, keyspace, DIGEST));
        assertFalse(ledger.claimArchive(
                ARCHIVE, SOURCE, DESTINATION, LINE, keyspace, DIGEST));
        assertEquals(SOURCE, ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
    }

    @Test
    public void archiveApisRejectNonCanonicalInputs() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        String keyspace = ledger.register(SOURCE, LINE);

        assertThrows(IllegalArgumentException.class,
                () -> ledger.sealArchive(SOURCE, "not-a-uuid", LINE, keyspace, DIGEST));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.sealArchive(SOURCE, ARCHIVE, LINE, keyspace, DIGEST.toUpperCase()));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.resolveArchivedOwner(
                        ARCHIVE, "com.facebook.lite", keyspace, DIGEST));
    }

    @Test
    public void malformedPersistedReservationFailsClosed() throws Exception {
        java.io.File filesDir = temporaryFolder.newFolder();
        CustodianLedger ledger = new CustodianLedger(filesDir);
        String keyspace = ledger.register(SOURCE, LINE);
        java.io.File record = new java.io.File(
                filesDir, "keyspaces/" + keyspace + "/" + LINE + ".properties");
        java.nio.file.Files.write(
                record.toPath(),
                ("schemaVersion=2\n"
                        + "keyspaceId=" + keyspace + "\n"
                        + "ownerSpaceId=" + SOURCE + "\n"
                        + "packageName=" + LINE + "\n"
                        + "archiveId=" + ARCHIVE + "\n"
                        + "archiveSha256=bad\n").getBytes());

        assertThrows(
                IllegalStateException.class,
                () -> ledger.resolveArchivedOwner(ARCHIVE, LINE, keyspace, DIGEST));
    }

    @Test
    public void legacyOwnershipRecordRemainsReadable() throws Exception {
        java.io.File filesDir = temporaryFolder.newFolder();
        java.io.File record = new java.io.File(
                filesDir, "keyspaces/" + SOURCE + "/" + LINE + ".properties");
        assertTrue(record.getParentFile().mkdirs());
        java.nio.file.Files.write(
                record.toPath(),
                ("schemaVersion=1\n"
                        + "keyspaceId=" + SOURCE + "\n"
                        + "ownerSpaceId=" + SOURCE + "\n"
                        + "packageName=" + LINE + "\n").getBytes());

        CustodianLedger ledger = new CustodianLedger(filesDir);
        assertEquals(SOURCE, ledger.resolve(SOURCE, LINE));
        assertTrue(ledger.sealArchive(SOURCE, ARCHIVE, LINE, SOURCE, DIGEST));
        assertEquals(SOURCE, ledger.resolveArchivedOwner(ARCHIVE, LINE, SOURCE, DIGEST));
    }

    @Test
    public void rejectsUnsupportedPackagesAndMalformedIds() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        assertThrows(IllegalArgumentException.class,
                () -> ledger.register(SOURCE, "com.facebook.lite"));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.register("not-a-uuid", LINE));
    }
}
