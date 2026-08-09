package org.apptwin.diagnostics

import org.apptwin.compatibility.CompatibilityLevel
import org.apptwin.operations.OperationKind
import org.apptwin.operations.OperationPhase
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.spaces.SpaceLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExporterTest {
    @Test
    fun `export pseudonymizes identifiers and emits only allowlisted state`() {
        val snapshot = DiagnosticSnapshot(
            appVersion = "0.2.0-m1",
            androidApi = 31,
            spaces = listOf(
                DiagnosticSpaceSnapshot(
                    spaceId = "private-space-id",
                    lifecycle = SpaceLifecycleState.READY,
                    clones = listOf(
                        DiagnosticCloneSnapshot(
                            packageName = "com.private.bank",
                            lifecycle = CloneLifecycleState.NEEDS_REPAIR,
                            compatibility = CompatibilityLevel.PARTIAL,
                        ),
                    ),
                ),
            ),
            operations = listOf(
                DiagnosticOperationSnapshot(
                    OperationKind.REPAIR_CLONE,
                    OperationPhase.FAILED,
                    "REPAIR_FAILED",
                ),
            ),
        )

        val report = DiagnosticsExporter.export(snapshot, ByteArray(16) { 7 }).text

        assertFalse(report.contains("private-space-id"))
        assertFalse(report.contains("com.private.bank"))
        assertTrue(report.contains("state=NEEDS_REPAIR"))
        assertTrue(report.contains("failureCode=REPAIR_FAILED"))
        assertFalse(report.contains("/data/"))
        assertFalse(report.contains("token"))
    }

    @Test
    fun `same salt is stable while a new salt prevents cross-export correlation`() {
        val snapshot = DiagnosticSnapshot(
            "1.0",
            36,
            listOf(
                DiagnosticSpaceSnapshot(
                    "space-sensitive-id",
                    SpaceLifecycleState.READY,
                    emptyList(),
                ),
            ),
            emptyList(),
        )
        val first = DiagnosticsExporter.export(snapshot, ByteArray(16) { 1 })
        val repeated = DiagnosticsExporter.export(snapshot, ByteArray(16) { 1 })
        val differentSalt = DiagnosticsExporter.export(snapshot, ByteArray(16) { 2 })

        assertEquals(first, repeated)
        assertFalse(first == differentSalt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short export salt is rejected`() {
        DiagnosticsExporter.export(
            DiagnosticSnapshot("1", 31, emptyList(), emptyList()),
            ByteArray(8),
        )
    }
}
