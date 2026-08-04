package com.lody.virtual.client.env;

import static org.junit.Assert.assertEquals;

import android.content.Intent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class SpecialComponentListTest {
    @Test
    public void protectActionsSupportsLegacyListStorage() {
        ArrayList<String> actions = new ArrayList<>(Arrays.asList(
                "com.example.CUSTOM",
                Intent.ACTION_SCREEN_ON,
                "android.appwidget.action.APPWIDGET_UPDATE"
        ));

        SpecialComponentList.protectActions(actions);

        assertEquals(Arrays.asList(
                "_VA_protected_com.example.CUSTOM",
                Intent.ACTION_SCREEN_ON
        ), actions);
    }

    @Test
    public void protectActionsSupportsArraySetCollectionContract() {
        Set<String> actions = new LinkedHashSet<>(Arrays.asList(
                "com.example.CUSTOM",
                Intent.ACTION_SCREEN_ON,
                "android.appwidget.action.APPWIDGET_UPDATE"
        ));

        SpecialComponentList.protectActions(actions);

        assertEquals(new LinkedHashSet<>(Arrays.asList(
                "_VA_protected_com.example.CUSTOM",
                Intent.ACTION_SCREEN_ON
        )), actions);
    }

    @Test
    public void protectActionsLeavesAlreadyProtectedSetStable() {
        Set<String> actions = new LinkedHashSet<>(Arrays.asList(
                "_VA_protected_com.example.CUSTOM",
                Intent.ACTION_SCREEN_ON
        ));

        SpecialComponentList.protectActions(actions);

        assertEquals(new LinkedHashSet<>(Arrays.asList(
                "_VA_protected_com.example.CUSTOM",
                Intent.ACTION_SCREEN_ON
        )), actions);
    }

    @Test
    public void protectActionsDropsGoogleProtoStoreProcessSignals() {
        ArrayList<String> actions = new ArrayList<>(Arrays.asList(
                SpecialComponentList.GOOGLE_PROTOSTORE_ACTION_PREFIX + "SIGNAL_ACTION",
                SpecialComponentList.GOOGLE_PROTOSTORE_ACTION_PREFIX + "MULTI_APP",
                "com.example.CUSTOM"
        ));

        SpecialComponentList.protectActions(actions);

        assertEquals(Arrays.asList("_VA_protected_com.example.CUSTOM"), actions);
    }
}
