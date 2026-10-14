package com.lody.virtual.helper.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpaceAccountPickerPolicyTest {
    @Test public void redirectsOnlyExactFrameworkAccountPicker() {
        assertTrue(SpaceAccountPickerPolicy.isFrameworkComponent("android", "android.accounts.ChooseTypeAndAccountActivity"));
        assertFalse(SpaceAccountPickerPolicy.isFrameworkComponent("other", "android.accounts.ChooseTypeAndAccountActivity"));
        assertFalse(SpaceAccountPickerPolicy.isFrameworkComponent("android", "android.accounts.ChooseAccountTypeActivity"));
        assertFalse(SpaceAccountPickerPolicy.isFrameworkComponent("android", "other.ChooseTypeAndAccountActivity"));
        assertFalse(SpaceAccountPickerPolicy.isFrameworkComponent(null, null));
    }
    @Test public void rejectsReusedUserAndChangedPackageOwnership() {
        assertTrue(SpaceAccountPickerPolicy.matchesGeneration(1, 10, 20, 1, 10, 20));
        assertFalse(SpaceAccountPickerPolicy.matchesGeneration(1, 10, 20, 2, 10, 20));
        assertFalse(SpaceAccountPickerPolicy.matchesGeneration(1, 10, 20, 1, 11, 20));
        assertFalse(SpaceAccountPickerPolicy.matchesGeneration(1, 10, 20, 1, 10, 21));
        assertFalse(SpaceAccountPickerPolicy.matchesGeneration(-1, 10, 20, -1, 10, 20));
        assertFalse(SpaceAccountPickerPolicy.matchesGeneration(1, 10, 0, 1, 10, 0));
    }
    @Test public void legacyPrimaryUserZeroEpochStillRequiresExactGenerationAndSourceTokenAtBoundary() {
        assertTrue(SpaceAccountPickerPolicy.matchesGeneration(0, 0, 20, 0, 0, 20));
        assertFalse(SpaceAccountPickerPolicy.matchesGeneration(0, 0, 20, 0, 1, 20));
    }
}
