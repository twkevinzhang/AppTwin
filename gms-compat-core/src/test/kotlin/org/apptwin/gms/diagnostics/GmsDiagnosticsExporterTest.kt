package org.apptwin.gms.diagnostics

import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityStatus
import org.apptwin.gms.capabilities.GmsEvidenceTier
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.operations.GmsOperationKind
import org.apptwin.gms.operations.GmsOperationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsDiagnosticsExporterTest {
    @Test
    fun `exports only allowlisted states and pseudonymizes group identity`() {
        val rawGroup = "private-work-profile@example.test"
        val report = GmsDiagnosticsExporter.export(
            snapshot(rawGroup),
            ByteArray(16) { it.toByte() },
        ).text

        assertFalse(report.contains(rawGroup))
        assertFalse(report.contains("account", ignoreCase = true))
        assertFalse(report.contains("checkin", ignoreCase = true))
        assertFalse(report.contains("oauth", ignoreCase = true))
        assertFalse(report.contains("filesystem", ignoreCase = true))
        assertTrue(report.contains("desired=ENABLED,observed=DEGRADED"))
        assertTrue(report.contains("capability=FCM_MESSAGE,status=FIXTURE_PASSED_EXTERNAL_UNTESTED"))
        assertTrue(report.endsWith("\n"))
    }

    @Test
    fun `per export salt prevents stable cross export group identifier`() {
        val first = GmsDiagnosticsExporter.export(snapshot("group-a"), ByteArray(16) { 1 }).text
        val second = GmsDiagnosticsExporter.export(snapshot("group-a"), ByteArray(16) { 2 }).text

        assertNotEquals(profilePseudonym(first), profilePseudonym(second))
    }

    @Test
    fun `billing and integrity diagnostic statuses remain explicit unsupported`() {
        val report = GmsDiagnosticsExporter.export(
            snapshot("group-a").copy(
                capabilities = listOf(
                    GmsDiagnosticCapability(
                        GmsCapability.PLAY_BILLING,
                        GmsCapabilityStatus.UNSUPPORTED,
                    ),
                    GmsDiagnosticCapability(
                        GmsCapability.PLAY_INTEGRITY,
                        GmsCapabilityStatus.UNSUPPORTED,
                    ),
                ),
            ),
            ByteArray(16) { 3 },
        ).text

        assertEquals(2, report.lineSequence().count { it.endsWith("status=UNSUPPORTED") })
    }

    private fun snapshot(groupId: String) = GmsDiagnosticSnapshot(
        appTwinVersion = "1.0.0",
        androidApi = 31,
        activeReleaseVersion = "0.3.10.250932",
        profiles = listOf(
            GmsDiagnosticProfile(
                groupId = groupId,
                desiredState = GmsDesiredState.ENABLED,
                observedState = GmsObservedState.DEGRADED,
                failureCode = "NETWORK_UNAVAILABLE",
            ),
        ),
        operations = listOf(
            GmsDiagnosticOperation(
                kind = GmsOperationKind.ENABLE,
                phase = GmsOperationPhase.FAILED_RETRYABLE,
                attempt = 2,
                failureCode = "NETWORK_UNAVAILABLE",
            ),
        ),
        capabilities = listOf(
            GmsDiagnosticCapability(
                capability = GmsCapability.FCM_MESSAGE,
                status = GmsCapabilityStatus.FIXTURE_PASSED_EXTERNAL_UNTESTED,
                evidenceTier = GmsEvidenceTier.ASUS_FIXTURE,
            ),
        ),
    )

    private fun profilePseudonym(report: String): String = report
        .lineSequence()
        .first { it.startsWith("profile=") }
        .substringAfter("profile=")
        .substringBefore(',')
}
