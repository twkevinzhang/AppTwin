package org.apptwin.runtime

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import org.apptwin.groups.FileGroupStore

internal data class RuntimeDiagnostics(
    val groupId: String,
    val environmentBindingId: Int,
    val packageName: String,
    val runtimeDataDirectory: String,
)

internal fun interface RuntimeDiagnosticsSink {
    fun persist(diagnostics: RuntimeDiagnostics)
}

internal class FileRuntimeDiagnosticsSink internal constructor(
    private val filesRoot: File,
) : RuntimeDiagnosticsSink {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    override fun persist(diagnostics: RuntimeDiagnostics) {
        val groupData = File(
            filesRoot,
            "groups/${diagnostics.groupId}/${FileGroupStore.DATA_DIRECTORY}",
        )
        check(groupData.isDirectory) { "Group data directory does not exist" }
        val mappingFile = File(groupData, FileGroupStore.RUNTIME_METADATA)
        val properties = Properties().apply {
            if (mappingFile.isFile) FileInputStream(mappingFile).use(::load)
            setProperty("groupId", diagnostics.groupId)
            setProperty("environmentBindingId", diagnostics.environmentBindingId.toString())
            setProperty(
                "runtimeDataDirectory.${diagnostics.packageName}",
                diagnostics.runtimeDataDirectory,
            )
        }
        val replacement = File(groupData, ".${mappingFile.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(replacement).use { output ->
                properties.store(output, "AppTwin Group runtime diagnostics")
                output.fd.sync()
            }
            Files.move(
                replacement.toPath(),
                mappingFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            replacement.delete()
        }
    }
}

internal class RuntimeLaunchRecorder(
    private val sink: RuntimeDiagnosticsSink,
    private val onFailure: (Throwable) -> Unit,
) {
    fun record(started: RuntimeLaunchResult.Started, diagnostics: RuntimeDiagnostics) =
        started.also {
            runCatching { sink.persist(diagnostics) }.onFailure(onFailure)
        }
}
