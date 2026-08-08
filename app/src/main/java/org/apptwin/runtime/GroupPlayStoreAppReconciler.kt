package org.apptwin.runtime

import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppOrigin

data class PlayStoreAppReconciliation(
    val additions: List<String>,
    val removals: List<String>,
)

/** Pure comparison between runtime truth and persisted Play Store-owned Group metadata. */
object GroupPlayStoreAppReconciler {
    fun plan(
        existingApps: List<GroupApp>,
        installedPackages: Collection<String>,
    ): PlayStoreAppReconciliation {
        val installed = installedPackages.toSet()
        val existingPackages = existingApps.mapTo(linkedSetOf(), GroupApp::packageName)
        val playStorePackages = existingApps.asSequence()
            .filter { it.origin == GroupAppOrigin.PLAY_STORE }
            .map(GroupApp::packageName)
            .toSet()
        return PlayStoreAppReconciliation(
            additions = (installed - existingPackages).sorted(),
            removals = (playStorePackages - installed).sorted(),
        )
    }
}

/** Runtime packages that are infrastructure rather than user-installed Group apps. */
object GroupVirtualPackageInventory {
    fun shouldExpose(packageName: String, hostPackageName: String): Boolean =
        packageName != hostPackageName &&
            packageName !in GroupAppRuntimeSupport.googlePackages
}
