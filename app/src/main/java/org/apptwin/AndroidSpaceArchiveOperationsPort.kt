package org.apptwin

import android.net.Uri
import org.apptwin.archive.AndroidSpaceArchiveOperations
import org.apptwin.archive.SpaceArchiveCompression

/** Android implementation of the SpaceArchiveOperations feature boundary. */
internal class AndroidSpaceArchiveOperationsPort(
    private val archives: AndroidSpaceArchiveOperations,
) : SpaceArchiveOperations {
    override suspend fun globalBackupLocation(): String? = archives.globalBackupLocation()

    override suspend fun deleteBackup(id: String) = archives.deleteBackup(id)

    override suspend fun setGlobalBackupLocation(uri: Uri) = archives.setGlobalBackupLocation(uri)

    override suspend fun exportToGlobalLocation(groupId: String, compression: SpaceArchiveCompression) = archives.exportToGlobalLocation(groupId,compression)

    override suspend fun exportSpace(
        groupId: String,
        destination: Uri,
        compression: SpaceArchiveCompression,
    ): SpaceArchiveExportResult = archives.exportSpace(groupId, destination, compression)

    override suspend fun createSpaceFromBackup(source: Uri): CreateSpaceFromBackupResult =
        archives.createSpaceFromBackup(source)
}
