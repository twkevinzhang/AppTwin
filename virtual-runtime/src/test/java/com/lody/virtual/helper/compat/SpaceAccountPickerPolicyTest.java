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
}
