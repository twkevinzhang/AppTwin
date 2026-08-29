package org.apptwin.runtime

import com.lody.virtual.client.hook.proxies.keystore.CustodianKeyOwnerPolicy
import org.apptwin.groups.GroupAppState

/** Keeps legacy Spaces unchanged while activating Custodian for newly added LINE clones. */
internal object CustodianActivationPolicy {
    fun shouldPrepare(
        packageName: String,
        hasExistingKeyspace: Boolean,
        appState: GroupAppState,
    ): Boolean =
        CustodianKeyOwnerPolicy.requiresCustodian(packageName) &&
            (hasExistingKeyspace || appState == GroupAppState.ADDED)
}
