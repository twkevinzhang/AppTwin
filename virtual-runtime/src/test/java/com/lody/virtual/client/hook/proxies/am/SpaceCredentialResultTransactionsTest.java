package com.lody.virtual.client.hook.proxies.am;

import org.junit.Test;
import android.os.IBinder;
import android.app.servertransaction.ActivityResultItem;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SpaceCredentialResultTransactionsTest {
    @Test public void mixedResultsPreserveGuestOrdering() {
        Object first = new Object();
        Object owned = new Object();
        Object last = new Object();
        List<Object> results = new ArrayList<>(Arrays.asList(first, owned, last));
        SpaceCredentialResultTransactions.filter(results, null, (token, result) -> result == owned);
        assertEquals(Arrays.asList(first, last), results);
    }

    @Test public void noOwnedResultLeavesEverythingIntact() {
        List<Object> results = new ArrayList<>(Arrays.asList(new Object(), new Object()));
        List<Object> before = new ArrayList<>(results);
        SpaceCredentialResultTransactions.filter(results, null, (token, result) -> false);
        assertEquals(before, results);
    }

    @Test public void unknownTransactionShapeDoesNotCallConsumer() {
        SpaceCredentialResultTransactions.intercept(new Object(), (token, result) -> {
            fail("Unknown transaction must not be consumed"); return false;
        });
    }
    public static final class Transaction {
        public final List<Object> mTransactionItems;
        public Transaction(Object... items) { mTransactionItems = Arrays.asList(items); }
    }

    @Test public void reflectiveTransactionKeepsOtherCallbacksAndFiltersOwnedResultOnly() {
        IBinder token = (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (proxy, method, args) -> null);
        Object owned = new Object();
        Object guest = new Object();
        Object lifecycle = new Object();
        ActivityResultItem item = new ActivityResultItem(token, Arrays.asList(owned, guest));
        Transaction transaction = new Transaction(item, lifecycle);
        SpaceCredentialResultTransactions.intercept(transaction, (owner, result) -> {
            assertSame(token, owner);
            return result == owned;
        });
        assertEquals(Arrays.asList(guest), item.mResultInfoList);
        assertEquals(Arrays.asList(item, lifecycle), transaction.mTransactionItems);
    }
}
