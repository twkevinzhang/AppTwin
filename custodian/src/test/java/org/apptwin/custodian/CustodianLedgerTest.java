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
    public void rejectsUnsupportedPackagesAndMalformedIds() throws Exception {
        CustodianLedger ledger = new CustodianLedger(temporaryFolder.newFolder());
        assertThrows(IllegalArgumentException.class,
                () -> ledger.register(SOURCE, "com.facebook.lite"));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.register("not-a-uuid", LINE));
    }
}
