package org.apptwin

enum class SpaceBackgroundStatus { UNKNOWN, STARTING, SUCCESS, FAILED, DISABLED }
data class SpaceBackgroundHealth(
    val groupId: String,
    val microg: SpaceBackgroundStatus = SpaceBackgroundStatus.UNKNOWN,
    val tcp: SpaceBackgroundStatus = SpaceBackgroundStatus.UNKNOWN,
    val sessionVerified: Boolean = false,
    val checkedAtMillis: Long = 0,
    val error: String? = null,
) {
    val healthy: Boolean get() = microg == SpaceBackgroundStatus.SUCCESS && tcp == SpaceBackgroundStatus.SUCCESS && sessionVerified
    val recovering: Boolean get() = microg == SpaceBackgroundStatus.STARTING || tcp == SpaceBackgroundStatus.STARTING
    val retryable: Boolean get() = !recovering && microg != SpaceBackgroundStatus.DISABLED && tcp != SpaceBackgroundStatus.DISABLED && (microg == SpaceBackgroundStatus.FAILED || tcp == SpaceBackgroundStatus.FAILED || microg == SpaceBackgroundStatus.UNKNOWN || tcp == SpaceBackgroundStatus.UNKNOWN || error != null || (!sessionVerified && microg == SpaceBackgroundStatus.SUCCESS && tcp == SpaceBackgroundStatus.SUCCESS))
}

internal fun mapSpaceBackgroundHealth(groupId: String, state: com.lody.virtual.remote.TrustedGmsCloudMessagingState): SpaceBackgroundHealth {
    val microg = when {
        state.phase == com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_DISABLED -> SpaceBackgroundStatus.DISABLED
        state.processAlive && state.bindingAlive -> SpaceBackgroundStatus.SUCCESS
        state.phase == com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_STARTING -> SpaceBackgroundStatus.STARTING
        state.phase == com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_DEGRADED -> SpaceBackgroundStatus.FAILED
        else -> SpaceBackgroundStatus.UNKNOWN
    }
    val tcp = if (state.phase == com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_DISABLED) SpaceBackgroundStatus.DISABLED else if (state.phase == com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_STARTING) SpaceBackgroundStatus.STARTING else when (state.tcpState) {
        com.lody.virtual.remote.TrustedGmsCloudMessagingState.TCP_CONNECTED -> SpaceBackgroundStatus.SUCCESS
        com.lody.virtual.remote.TrustedGmsCloudMessagingState.TCP_DISCONNECTED -> SpaceBackgroundStatus.FAILED
        else -> SpaceBackgroundStatus.UNKNOWN
    }
    return SpaceBackgroundHealth(groupId, microg, tcp,
        state.mcsSessionVerified && state.phase == com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_CONNECTED && state.processAlive && state.bindingAlive && state.tcpState == com.lody.virtual.remote.TrustedGmsCloudMessagingState.TCP_CONNECTED,
        state.observedAtMillis,
        if (state.phase == com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_DEGRADED) "推播連線異常，請重新連線。" else null)
}
