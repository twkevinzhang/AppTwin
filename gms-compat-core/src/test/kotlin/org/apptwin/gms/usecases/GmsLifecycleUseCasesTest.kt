package org.apptwin.gms.usecases

import java.util.ArrayDeque
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.model.GmsProfile
import org.apptwin.gms.operations.GmsOperation
import org.apptwin.gms.operations.GmsOperationKind
import org.apptwin.gms.operations.GmsOperationPhase
import org.apptwin.gms.operations.GmsOperationStore
import org.apptwin.gms.ports.ActiveGmsReleasePort
import org.apptwin.gms.ports.GmsProfileRepository
import org.apptwin.gms.ports.GmsResetMode
import org.apptwin.gms.ports.GmsRuntimeMutationResult
import org.apptwin.gms.ports.GmsRuntimeObservation
import org.apptwin.gms.ports.GmsRuntimePort
import org.apptwin.gms.trustedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsLifecycleUseCasesTest {
    private val groupA = GmsGroupId("group-a")
    private val groupB = GmsGroupId("group-b")

    @Test
    fun `enable requires consent before touching runtime`() {
        val fixture = Fixture()

        assertEquals(GmsLifecycleResult.ConsentRequired, fixture.coordinator.enable(groupA))
        assertTrue(fixture.runtime.calls.isEmpty())
        assertTrue(fixture.operations.values.isEmpty())
    }

    @Test
    fun `shared release enables only requested group and repeat is idempotent`() {
        val fixture = Fixture()
        fixture.grantConsent(groupA)
        fixture.grantConsent(groupB)

        val first = fixture.coordinator.enable(groupA)
        val second = fixture.coordinator.enable(groupA)

        assertTrue(first is GmsLifecycleResult.Completed)
        assertTrue(second is GmsLifecycleResult.AlreadySatisfied)
        assertEquals("release-1", fixture.profiles.find(groupA)?.observedReleaseId)
        assertEquals(GmsObservedState.ABSENT, fixture.profiles.find(groupB)?.observedState)
        assertTrue(fixture.runtime.states.getValue(groupA).installed)
        assertFalse(fixture.runtime.states.containsKey(groupB))
        assertEquals(1, fixture.runtime.calls.count { it.startsWith("enable:") })
    }

    @Test
    fun `all enabled groups observe the same active shared release`() {
        val fixture = Fixture(activeReleaseId = "release-shared")
        fixture.grantConsent(groupA)
        fixture.grantConsent(groupB)

        fixture.coordinator.enable(groupA)
        fixture.coordinator.enable(groupB)

        assertEquals("release-shared", fixture.profiles.find(groupA)?.observedReleaseId)
        assertEquals("release-shared", fixture.profiles.find(groupB)?.observedReleaseId)
        assertEquals("release-shared", fixture.runtime.states.getValue(groupA).releaseId)
        assertEquals("release-shared", fixture.runtime.states.getValue(groupB).releaseId)
    }

    @Test
    fun `retry reuses operation id and converges after crash safe failure`() {
        val fixture = Fixture()
        fixture.grantConsent(groupA)
        fixture.runtime.results += GmsRuntimeMutationResult.RetryableFailure("NETWORK_UNAVAILABLE")

        val first = fixture.coordinator.enable(groupA) as GmsLifecycleResult.RetryScheduled
        val pending = fixture.operations.find(first.operationId)!!
        assertEquals(GmsOperationPhase.FAILED_RETRYABLE, pending.phase)
        assertEquals(2, pending.attempt)

        val second = fixture.coordinator.enable(groupA)

        assertTrue(second is GmsLifecycleResult.Completed)
        assertNull(fixture.operations.find(first.operationId))
        val operationIds = fixture.runtime.calls
            .filter { it.startsWith("enable:") }
            .map { it.substringAfterLast(':') }
        assertEquals(listOf(first.operationId, first.operationId), operationIds)
    }

    @Test
    fun `later disable supersedes retryable enable and recovery cannot reverse intent`() {
        val fixture = Fixture()
        fixture.grantConsent(groupA)
        fixture.runtime.results += GmsRuntimeMutationResult.RetryableFailure("NETWORK_UNAVAILABLE")
        val failedEnable = fixture.coordinator.enable(groupA) as GmsLifecycleResult.RetryScheduled

        val disabled = fixture.coordinator.disable(groupA)
        val reconciliation = fixture.reconciler.execute()

        assertTrue(
            disabled is GmsLifecycleResult.Completed ||
                disabled is GmsLifecycleResult.AlreadySatisfied,
        )
        assertNull(fixture.operations.find(failedEnable.operationId))
        assertTrue(reconciliation.completed.isEmpty())
        assertFalse(fixture.runtime.states.containsKey(groupA))
        assertEquals(GmsDesiredState.DISABLED, fixture.profiles.find(groupA)?.desiredState)
    }

    @Test
    fun `expired trusted manifest cannot enable runtime`() {
        val fixture = Fixture(initialNow = 10_000)
        fixture.grantConsent(groupA)

        val result = fixture.coordinator.enable(groupA)

        assertEquals(GmsLifecycleResult.TrustedReleaseUnavailable, result)
        assertTrue(fixture.runtime.calls.isEmpty())
        assertTrue(fixture.operations.values.isEmpty())
    }

    @Test
    fun `reconcile replays operation after runtime applied but profile commit crashed`() {
        val fixture = Fixture()
        fixture.grantConsent(groupA)
        val operation = fixture.operation(GmsOperationKind.ENABLE, GmsDesiredState.ENABLED)
        fixture.operations.save(operation)
        fixture.runtime.states[groupA] = GmsRuntimeObservation(groupA, true, "release-1", true)

        val result = fixture.reconciler.execute()

        assertEquals(listOf(operation.id), result.completed)
        assertEquals(GmsObservedState.READY_PARTIAL, fixture.profiles.find(groupA)?.observedState)
        assertEquals("release-1", fixture.profiles.find(groupA)?.observedReleaseId)
        assertNull(fixture.operations.find(operation.id))
    }

    @Test
    fun `reconcile cleans committed marker without replaying runtime`() {
        val fixture = Fixture()
        val operation = fixture.operation(
            GmsOperationKind.DISABLE,
            GmsDesiredState.DISABLED,
        ).copy(phase = GmsOperationPhase.COMMITTED)
        fixture.operations.save(operation)

        val result = fixture.reconciler.execute()

        assertEquals(listOf(operation.id), result.completed)
        assertTrue(fixture.runtime.calls.isEmpty())
        assertNull(fixture.operations.find(operation.id))
    }

    @Test
    fun `pending enable from old shared release is not silently applied`() {
        val fixture = Fixture(activeReleaseId = "release-2")
        val old = fixture.operation(
            GmsOperationKind.ENABLE,
            GmsDesiredState.ENABLED,
            releaseId = "release-1",
        )
        fixture.operations.save(old)

        val result = fixture.reconciler.execute()

        assertEquals(listOf(old.id), result.releaseMismatches)
        assertTrue(fixture.runtime.calls.isEmpty())
        assertEquals(old, fixture.operations.find(old.id))
    }

    @Test
    fun `reset requires confirmation and does not affect another group`() {
        val fixture = Fixture()
        fixture.grantConsent(groupA)
        fixture.grantConsent(groupB)
        fixture.coordinator.enable(groupA)
        fixture.coordinator.enable(groupB)

        assertEquals(
            GmsLifecycleResult.DestructiveConfirmationRequired,
            fixture.coordinator.reset(groupA, reenable = false, destructiveConfirmed = false),
        )
        val reset = fixture.coordinator.reset(
            groupA,
            reenable = false,
            destructiveConfirmed = true,
        )

        assertTrue(reset is GmsLifecycleResult.Completed)
        assertFalse(fixture.runtime.states.containsKey(groupA))
        assertTrue(fixture.runtime.states.getValue(groupB).installed)
        assertEquals(GmsObservedState.ABSENT, fixture.profiles.find(groupA)?.observedState)
        assertEquals(GmsObservedState.READY_PARTIAL, fixture.profiles.find(groupB)?.observedState)
    }

    @Test
    fun `profile evaluation marks installed old shared release as update required`() {
        val fixture = Fixture(activeReleaseId = "release-2")
        fixture.profiles.save(
            GmsProfile.disabled(groupA).copy(
                networkConsent = GmsNetworkConsent.GRANTED,
                desiredState = GmsDesiredState.ENABLED,
                observedState = GmsObservedState.READY_PARTIAL,
                observedReleaseId = "release-1",
            ),
        )
        fixture.runtime.states[groupA] = GmsRuntimeObservation(groupA, true, "release-1", true)

        val evaluated = EvaluateGmsProfileUseCase(
            fixture.profiles,
            fixture.releases,
            fixture.runtime,
        ).execute(groupA)

        assertEquals(GmsObservedState.UPDATE_REQUIRED, evaluated.observedState)
        assertEquals("release-1", evaluated.observedReleaseId)
    }

    private class Fixture(
        activeReleaseId: String = "release-1",
        initialNow: Long = 1_000,
    ) {
        val profiles = MemoryProfiles()
        val operations = MemoryOperations()
        val runtime = FakeRuntime()
        val releases = ActiveGmsReleasePort { trustedManifest(activeReleaseId) }
        private var now = initialNow
        private var id = 0
        val coordinator = GmsLifecycleCoordinator(
            profiles,
            operations,
            releases,
            runtime,
            idFactory = { "00000000-0000-0000-0000-${(++id).toString().padStart(12, '0')}" },
            clock = { now++ },
        )
        val reconciler = ReconcileGmsUseCase(coordinator, operations, releases)

        fun grantConsent(groupId: GmsGroupId) {
            profiles.save(
                (profiles.find(groupId) ?: GmsProfile.disabled(groupId)).copy(
                    networkConsent = GmsNetworkConsent.GRANTED,
                ),
            )
        }

        fun operation(
            kind: GmsOperationKind,
            desired: GmsDesiredState,
            groupId: GmsGroupId = GmsGroupId("group-a"),
            releaseId: String? = if (desired == GmsDesiredState.ENABLED) {
                releases.current()!!.release.releaseId
            } else {
                null
            },
        ) = GmsOperation(
            id = "10000000-0000-0000-0000-${(++id).toString().padStart(12, '0')}",
            groupId = groupId,
            kind = kind,
            targetDesiredState = desired,
            targetReleaseId = releaseId,
            phase = GmsOperationPhase.STARTED,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }

    private class MemoryProfiles : GmsProfileRepository {
        private val values = linkedMapOf<GmsGroupId, GmsProfile>()
        override fun find(groupId: GmsGroupId): GmsProfile? = values[groupId]
        override fun list(): List<GmsProfile> = values.values.toList()
        override fun save(profile: GmsProfile) {
            values[profile.groupId] = profile
        }
    }

    private class MemoryOperations : GmsOperationStore {
        val values = linkedMapOf<String, GmsOperation>()
        override fun list(): List<GmsOperation> = values.values.toList()
        override fun find(id: String): GmsOperation? = values[id]
        override fun save(operation: GmsOperation) {
            values[operation.id] = operation
        }
        override fun remove(id: String) {
            values.remove(id)
        }
    }

    private class FakeRuntime : GmsRuntimePort {
        val states = linkedMapOf<GmsGroupId, GmsRuntimeObservation>()
        val results = ArrayDeque<GmsRuntimeMutationResult>()
        val calls = mutableListOf<String>()

        override fun observe(groupId: GmsGroupId): GmsRuntimeObservation =
            states[groupId] ?: GmsRuntimeObservation(groupId, false)

        override fun ensureEnabled(
            groupId: GmsGroupId,
            release: org.apptwin.gms.artifacts.TrustedGmsManifest,
            operationId: String,
        ): GmsRuntimeMutationResult {
            calls += "enable:${groupId.value}:$operationId"
            results.pollFirst()?.let { return it }
            val target = GmsRuntimeObservation(groupId, true, release.release.releaseId, true)
            if (states[groupId] == target) return GmsRuntimeMutationResult.AlreadySatisfied(target)
            states[groupId] = target
            return GmsRuntimeMutationResult.Applied(target)
        }

        override fun ensureDisabled(
            groupId: GmsGroupId,
            operationId: String,
        ): GmsRuntimeMutationResult {
            calls += "disable:${groupId.value}:$operationId"
            results.pollFirst()?.let { return it }
            val target = GmsRuntimeObservation(groupId, false)
            if (!states.containsKey(groupId)) {
                return GmsRuntimeMutationResult.AlreadySatisfied(target)
            }
            states.remove(groupId)
            return GmsRuntimeMutationResult.Applied(target)
        }

        override fun resetPrivateState(
            groupId: GmsGroupId,
            mode: GmsResetMode,
            release: org.apptwin.gms.artifacts.TrustedGmsManifest?,
            operationId: String,
        ): GmsRuntimeMutationResult {
            calls += "reset:${groupId.value}:$operationId"
            results.pollFirst()?.let { return it }
            val target = when (mode) {
                GmsResetMode.DISABLE_AFTER_RESET -> GmsRuntimeObservation(groupId, false)
                GmsResetMode.REENABLE_ACTIVE_RELEASE -> GmsRuntimeObservation(
                    groupId,
                    true,
                    requireNotNull(release).release.releaseId,
                    true,
                )
            }
            if (mode == GmsResetMode.DISABLE_AFTER_RESET) states.remove(groupId) else states[groupId] = target
            return GmsRuntimeMutationResult.Applied(target)
        }
    }
}
