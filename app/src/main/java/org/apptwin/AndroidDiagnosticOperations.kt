package org.apptwin

import android.os.Build
import java.security.SecureRandom
import org.apptwin.compatibility.CompatibilityAssessmentPolicy
import org.apptwin.compatibility.CompatibilityLimitation
import org.apptwin.compatibility.DeviceValidation
import org.apptwin.compatibility.PackageCompatibilityFacts
import org.apptwin.diagnostics.DiagnosticCloneSnapshot
import org.apptwin.diagnostics.DiagnosticOperationSnapshot
import org.apptwin.diagnostics.DiagnosticSnapshot
import org.apptwin.diagnostics.DiagnosticSpaceSnapshot
import org.apptwin.diagnostics.DiagnosticsExporter
import org.apptwin.groups.FileGroupStore
import org.apptwin.operations.FileOperationRecordStore
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.runtime.GroupAppRuntimeSupport
import org.apptwin.spaces.SpaceStatePolicy

/** Android implementation of the DiagnosticOperations feature boundary. */
internal class AndroidDiagnosticOperations(
    private val groupStore: FileGroupStore,
    private val operationStore: FileOperationRecordStore,
    private val importer: AndroidPackageRevisionImporter,
) : DiagnosticOperations {
    override suspend fun exportDiagnostics(): String {
        val groupSnapshot = groupStore.loadSnapshot()
        val operations = operationStore.listPending()
        val entries = runCatching(importer::listCloneableApps).getOrDefault(emptyList())
            .associateBy { it.packageName }
        val spaces = groupSnapshot.groups.map { group ->
            val spaceState = SpaceStatePolicy.assess(group, operations)
            val cloneStates = spaceState.clones.associateBy { it.packageName }
            DiagnosticSpaceSnapshot(
                spaceId = group.id,
                lifecycle = spaceState.lifecycle,
                clones = group.apps.map { app ->
                    val source = entries[app.packageName]
                    val limitations = setOf(
                        CompatibilityLimitation.PUSH_DELIVERY,
                        CompatibilityLimitation.CAMERA,
                        CompatibilityLimitation.MICROPHONE,
                        CompatibilityLimitation.BACKGROUND_EXECUTION,
                    )
                    val validations = source?.let { installed ->
                        GroupAppRuntimeSupport.validation(
                            app.packageName,
                            installed.versionCode,
                            Build.VERSION.SDK_INT,
                        )
                    }?.let { validation ->
                        setOf(
                            DeviceValidation(
                                androidApi = validation.androidApi,
                                versionCode = validation.versionCode,
                            ),
                        )
                    }.orEmpty()
                    val assessment = CompatibilityAssessmentPolicy.assess(
                        PackageCompatibilityFacts(
                            packageName = app.packageName,
                            versionCode = source?.versionCode ?: 0,
                            sourceInstalled = source != null,
                            hasLauncher = source?.let { true },
                            abiSupported = null,
                            baseArtifactReadable = null,
                            splitSetComplete = null,
                            signatureTrusted = null,
                            requiredFeaturesSatisfied = null,
                            knownLimitations = limitations,
                            validations = validations,
                        ),
                    )
                    DiagnosticCloneSnapshot(
                        packageName = app.packageName,
                        lifecycle = cloneStates.getValue(app.packageName).lifecycle,
                        compatibility = assessment.level,
                    )
                },
            )
        }
        val report = DiagnosticsExporter.export(
            DiagnosticSnapshot(
                appVersion = BuildConfig.VERSION_NAME,
                androidApi = Build.VERSION.SDK_INT,
                spaces = spaces,
                operations = operations.map { operation ->
                    DiagnosticOperationSnapshot(
                        kind = operation.kind,
                        phase = operation.phase,
                        failureCode = operation.failureCode,
                    )
                },
            ),
            exportSalt = ByteArray(32).also(SecureRandom()::nextBytes),
        )
        return report.text
    }
}
