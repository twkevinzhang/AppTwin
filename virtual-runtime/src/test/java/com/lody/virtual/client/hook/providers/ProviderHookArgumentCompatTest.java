package com.lody.virtual.client.hook.providers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ProviderHookArgumentCompatTest {

    @Test
    public void android17UpdateShapeDoesNotReadPastBundleArgument() {
        Object[] args = {new Object(), new Object(), new Object(), null};

        assertNull(ProviderHook.optionalStringArg(args, 3));
        assertNull(ProviderHook.optionalStringArrayArg(args, 4));
    }

    @Test
    public void legacyUpdateShapeStillExposesSelectionArguments() {
        String[] selectionArgs = {"enabled"};
        Object[] args = {new Object(), new Object(), new Object(), "state = ?", selectionArgs};

        assertEquals("state = ?", ProviderHook.optionalStringArg(args, 3));
        assertArrayEquals(selectionArgs, ProviderHook.optionalStringArrayArg(args, 4));
    }

    @Test
    public void mismatchedOptionalArgumentTypesAreIgnored() {
        Object[] args = {new Object(), new Object(), new Object(), new Object(), "not-an-array"};

        assertNull(ProviderHook.optionalStringArg(args, 3));
        assertNull(ProviderHook.optionalStringArrayArg(args, 4));
    }
}
