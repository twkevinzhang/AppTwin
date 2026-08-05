package com.lody.virtual.client.hook.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SettingsProviderHookTest {
    @Test
    public void isolatesAndroid17RestrictedInputMethodSetting() {
        assertEquals("", SettingsProviderHook.presetValue("enabled_input_methods"));
        assertNull(SettingsProviderHook.presetValue("unrelated_setting"));
    }

    @Test
    public void allowsOnlyVirtualPresetThroughAndroid17ClientSideGuard() {
        Set<String> readable = new HashSet<>();
        assertEquals(true, SettingsProviderHook.allowClientSideRead(
                readable, "enabled_input_methods"));
        assertEquals(true, readable.contains("enabled_input_methods"));
        assertEquals(false, SettingsProviderHook.allowClientSideRead(
                readable, "unrelated_setting"));
        assertEquals(false, readable.contains("unrelated_setting"));
    }

    @Test
    public void raisesTargetSdkLimitOnlyForVirtualPreset() {
        Map<String, Object> restricted = new HashMap<>();
        restricted.put("enabled_input_methods", 33);
        assertEquals(true, SettingsProviderHook.removeClientSideTargetSdkLimit(
                restricted, "enabled_input_methods"));
        assertEquals(Integer.MAX_VALUE, restricted.get("enabled_input_methods"));
        assertEquals(false, SettingsProviderHook.removeClientSideTargetSdkLimit(
                restricted, "unrelated_setting"));
    }
}
