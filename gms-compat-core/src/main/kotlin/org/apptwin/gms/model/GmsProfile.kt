package org.apptwin.gms.model

@JvmInline
value class GmsGroupId(val value: String) {
    init {
        require(value.isNotBlank()) { "group id must not be blank" }
        require(value.length <= 128) { "group id is too long" }
    }
}

enum class GmsDesiredState {
    DISABLED,
    ENABLED,
}

enum class GmsObservedState {
    ABSENT,
    ENABLING,
    READY_PARTIAL,
    DEGRADED,
    DISABLING,
    RESETTING,
    UPDATE_REQUIRED,
    REVOKED,
}

enum class GmsNetworkConsent {
    NOT_GRANTED,
    GRANTED,
}

/**
 * Per-group desired and observed state. The APK revision is intentionally not configurable here:
 * every enabled group on one AppTwin installation uses the one active shared release.
 */
data class GmsProfile(
    val groupId: GmsGroupId,
    val desiredState: GmsDesiredState,
    val observedState: GmsObservedState,
    val networkConsent: GmsNetworkConsent,
    val observedReleaseId: String? = null,
    val generation: Long = 0,
    val failureCode: String? = null,
) {
    init {
        require(generation >= 0) { "generation must not be negative" }
        require(observedReleaseId == null || RELEASE_ID.matches(observedReleaseId)) {
            "observedReleaseId must be a stable release token"
        }
        require(failureCode == null || STABLE_CODE.matches(failureCode)) {
            "failureCode must be a stable machine-readable code"
        }
        require(observedState == GmsObservedState.DEGRADED || failureCode == null) {
            "failureCode is valid only for a degraded profile"
        }
        require(
            observedState !in setOf(GmsObservedState.READY_PARTIAL, GmsObservedState.UPDATE_REQUIRED) ||
                observedReleaseId != null,
        ) { "an installed observed state requires a release id" }
    }

    fun next(
        desiredState: GmsDesiredState = this.desiredState,
        observedState: GmsObservedState = this.observedState,
        observedReleaseId: String? = this.observedReleaseId,
        failureCode: String? = null,
    ): GmsProfile = copy(
        desiredState = desiredState,
        observedState = observedState,
        observedReleaseId = observedReleaseId,
        generation = generation + 1,
        failureCode = failureCode,
    )

    companion object {
        private val RELEASE_ID = Regex("[A-Za-z0-9._+-]{1,96}")
        private val STABLE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun disabled(groupId: GmsGroupId): GmsProfile = GmsProfile(
            groupId = groupId,
            desiredState = GmsDesiredState.DISABLED,
            observedState = GmsObservedState.ABSENT,
            networkConsent = GmsNetworkConsent.NOT_GRANTED,
        )
    }
}
