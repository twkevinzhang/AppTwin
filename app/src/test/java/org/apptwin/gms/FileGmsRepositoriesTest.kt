package org.apptwin.gms

import java.nio.file.Files
import java.util.UUID
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityEvidence
import org.apptwin.gms.capabilities.GmsEvidenceOutcome
import org.apptwin.gms.capabilities.GmsEvidenceTier
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.model.GmsProfile
import org.apptwin.gms.operations.GmsOperation
import org.apptwin.gms.operations.GmsOperationKind
import org.apptwin.gms.operations.GmsOperationPhase
import org.apptwin.groups.CURRENT_GROUP_SCHEMA_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileGmsRepositoriesTest {
    @Test
    fun `profile survives recreation under group private data root`() {
        val root = rootWithGroup()
        val syncs = mutableListOf<String>()
        val profile = GmsProfile(
            groupId = groupId,
            desiredState = GmsDesiredState.ENABLED,
            observedState = GmsObservedState.READY_PARTIAL,
            networkConsent = GmsNetworkConsent.GRANTED,
            observedReleaseId = "release-1",
            generation = 4,
        )

        FileGmsProfileRepository(root, directorySync = { syncs += it.absolutePath }).save(profile)

        assertEquals(profile, FileGmsProfileRepository(root, directorySync = {}).find(groupId))
        assertTrue(root.resolve("groups/${groupId.value}/data/gms/profile.properties").isFile)
        assertTrue(syncs.any { it.endsWith("/data/gms") })
        assertFalse(
            root.walkTopDown().any { it.isFile && it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `operation save replace and remove are durable per group`() {
        val root = rootWithGroup()
        val store = FileGmsOperationStore(root, directorySync = {})
        val started = operation()
        store.save(started)
        store.save(started.copy(phase = GmsOperationPhase.APPLYING, updatedAtEpochMillis = 11))

        assertEquals(
            GmsOperationPhase.APPLYING,
            FileGmsOperationStore(root, directorySync = {}).find(started.id)?.phase,
        )
        assertEquals(1, store.list().size)

        store.remove(started.id)
        assertNull(store.find(started.id))
    }

    @Test
    fun `capability evidence stays scoped to its group`() {
        val root = rootWithGroup()
        val other = GmsGroupId("22222222-2222-2222-2222-222222222222")
        createGroup(root, other)
        val store = FileGmsCapabilityEvidenceRepository(root, directorySync = {})
        val first = evidence(groupId, "fixture:local:1")
        val second = evidence(other, "fixture:asus:2").copy(tier = GmsEvidenceTier.ASUS_FIXTURE)

        store.save(first)
        store.save(second)

        assertEquals(
            listOf(first),
            FileGmsCapabilityEvidenceRepository(root, directorySync = {}).list(groupId),
        )
        assertEquals(listOf(second), store.list(other))
    }

    @Test
    fun `evidence ids that previously collided persist independently`() {
        val root = rootWithGroup()
        val store = FileGmsCapabilityEvidenceRepository(root, directorySync = {})
        val colon = evidence(groupId, "fixture:a:b")
        val underscore = evidence(groupId, "fixture:a_b")

        store.save(colon)
        store.save(underscore)

        assertEquals(setOf(colon, underscore), store.list(groupId).toSet())
        assertEquals(
            2,
            root.resolve("groups/${groupId.value}/data/gms/evidence")
                .listFiles().orEmpty().count { it.extension == "properties" },
        )
    }

    @Test
    fun `stale operation cannot recreate a deleted group`() {
        val root = rootWithGroup()
        root.resolve("groups/${groupId.value}").deleteRecursively()

        assertThrows(IllegalArgumentException::class.java) {
            FileGmsOperationStore(root, directorySync = {}).save(operation())
        }
        assertFalse(root.resolve("groups/${groupId.value}").exists())
    }

    @Test
    fun `corrupt metadata fails closed and records a user visible warning`() {
        val root = rootWithGroup()
        val gmsRoot = root.resolve("groups/${groupId.value}/data/gms").apply { mkdirs() }
        gmsRoot.resolve("profile.properties").writeText("schemaVersion=1\ngroupId=${groupId.value}\n")
        val issues = GmsDataIssueRecorder()
        val store = FileGmsProfileRepository(root, {}, issues)

        assertThrows(GmsDataCorruptionException::class.java) { store.find(groupId) }
        assertEquals(
            listOf(GmsDataWarning(groupId.value, "profile.properties")),
            store.warnings(),
        )
        assertTrue(gmsRoot.resolve("profile.properties").isFile)
    }

    private fun rootWithGroup() = Files.createTempDirectory("apptwin-gms-store").toFile().also {
        createGroup(it, groupId)
    }

    private fun createGroup(root: java.io.File, id: GmsGroupId) {
        root.resolve("groups/${id.value}/data").mkdirs()
        root.resolve("groups/${id.value}/group.properties").writeText(
            "schemaVersion=$CURRENT_GROUP_SCHEMA_VERSION\nid=${id.value}\n",
        )
    }

    private fun operation() = GmsOperation(
        id = UUID.randomUUID().toString(),
        groupId = groupId,
        kind = GmsOperationKind.ENABLE,
        targetDesiredState = GmsDesiredState.ENABLED,
        targetReleaseId = "release-1",
        phase = GmsOperationPhase.STARTED,
        startedAtEpochMillis = 10,
        updatedAtEpochMillis = 10,
    )

    private fun evidence(group: GmsGroupId, id: String) = GmsCapabilityEvidence(
        evidenceId = id,
        groupId = group,
        releaseId = "release-1",
        capability = GmsCapability.FCM_MESSAGE,
        tier = GmsEvidenceTier.LOCAL_FIXTURE,
        outcome = GmsEvidenceOutcome.PASSED,
        observedAtEpochMillis = 20,
        androidApi = 31,
        appTwinVersion = "0.1.0",
        fixtureOrClientVersion = "fixture-1",
    )

    private companion object {
        val groupId = GmsGroupId("11111111-1111-1111-1111-111111111111")
    }
}
