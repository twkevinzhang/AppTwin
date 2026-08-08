package org.apptwin.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPickerIconPolicyTest {
    @Test
    fun `guaranteed packages keep color icons`() {
        assertTrue(shouldUseColorIconInAppPicker("jp.naver.line.android"))
        assertTrue(shouldUseColorIconInAppPicker("com.shopee.tw"))
        assertTrue(shouldUseColorIconInAppPicker("com.discord"))
    }

    @Test
    fun `other packages use grayscale icons`() {
        assertFalse(shouldUseColorIconInAppPicker("com.google.android.youtube"))
        assertFalse(shouldUseColorIconInAppPicker("com.google.android.apps.maps"))
        assertFalse(shouldUseColorIconInAppPicker("com.example.line"))
    }
}
