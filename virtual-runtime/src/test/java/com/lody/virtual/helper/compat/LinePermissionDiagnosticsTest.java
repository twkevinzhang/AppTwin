package com.lody.virtual.helper.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public class LinePermissionDiagnosticsTest {
    @Test public void scopeAndMetadataAreStrictlyLimited() {
        assertTrue(LinePermissionDiagnostics.isExactScope("jp.naver.line.android", 2));
        assertFalse(LinePermissionDiagnostics.isExactScope("jp.naver.line.android", 1));
        assertFalse(LinePermissionDiagnostics.isExactScope("org.example.app", 2));
        assertFalse(LinePermissionDiagnostics.isExactScope(null, 2));
        assertTrue(LinePermissionDiagnostics.isAllowedPermission("android.permission.CAMERA"));
        assertFalse(LinePermissionDiagnostics.isAllowedPermission("android.permission.READ_CONTACTS"));
        assertFalse(LinePermissionDiagnostics.isAllowedPermission(null));
        assertFalse(LinePermissionDiagnostics.isAllowedStage("arbitrary"));
    }

    @Test public void budgetExpiresAtHardDeadlineAndCannotRestart() {
        LinePermissionDiagnostics.Budget budget = new LinePermissionDiagnostics.Budget();
        assertTrue(budget.reserve(100));
        assertTrue(budget.reserve(120_099));
        assertFalse(budget.reserve(120_100));
        assertFalse(budget.reserve(200_000));
    }

    @Test public void eventCapIsIndependentOfDeadline() {
        LinePermissionDiagnostics.Budget budget = new LinePermissionDiagnostics.Budget();
        for (int i = 0; i < 96; i++) assertTrue(budget.reserve(i));
        assertFalse(budget.reserve(96));
    }
}
