package com.lody.virtual.client.env;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.DownloadManager;
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
    public void registrationActionsSeparateExternalSystemSendersFromInternalActions() {
        Set<String> internalActions = new LinkedHashSet<>(Arrays.asList(
                "_VA_protected_com.example.CUSTOM",
                DownloadManager.ACTION_DOWNLOAD_COMPLETE,
                Intent.ACTION_SCREEN_ON
        ));
        Set<String> systemActions = new LinkedHashSet<>(internalActions);

        SpecialComponentList.retainSystemBroadcastActions(internalActions, false);
        SpecialComponentList.retainSystemBroadcastActions(systemActions, true);

        assertEquals(new LinkedHashSet<>(Arrays.asList(
                "_VA_protected_com.example.CUSTOM"
        )), internalActions);
        assertEquals(new LinkedHashSet<>(Arrays.asList(
                DownloadManager.ACTION_DOWNLOAD_COMPLETE,
                Intent.ACTION_SCREEN_ON
        )), systemActions);
    }

    @Test
    public void whitePermissionsDoNotGrantGoogleRuntimePermissions() {
        assertFalse(SpecialComponentList.isWhitePermission(
                "com.google.android.gms.settings.SECURITY_SETTINGS"));
        assertFalse(SpecialComponentList.isWhitePermission(
                "com.google.android.apps.plus.PRIVACY_SETTINGS"));
        assertTrue(SpecialComponentList.isWhitePermission(Manifest.permission.ACCOUNT_MANAGER));
    }

}
