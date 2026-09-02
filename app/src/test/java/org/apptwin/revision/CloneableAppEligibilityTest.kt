package org.apptwin.revision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneableAppEligibilityTest {
    @Test
    fun eligibleSourcePassesAllChecks() {
        assertTrue(eligible())
    }

    @Test
    fun missingPackageIsNotEligible() {
        assertFalse(eligible(packageExists = false))
    }

    @Test
    fun hostPackageIsNotEligible() {
        assertFalse(eligible(candidatePackageName = HOST_PACKAGE))
    }

    @Test
    fun disabledPackageIsNotEligible() {
        assertFalse(eligible(enabled = false))
    }

    @Test
    fun unreadableSourceIsNotEligible() {
        assertFalse(eligible(sourceReadable = false))
    }

    @Test
    fun packageWithoutLauncherIsNotEligible() {
        assertFalse(eligible(hasLauncherActivity = false))
    }

    @Test
    fun rejectedBasicCheckDoesNotReadSourceOrResolveLauncher() {
        var sourceChecked = false
        var launcherChecked = false

        val eligible = isCloneableAppEligible(
            hostPackageName = HOST_PACKAGE,
            candidatePackageName = HOST_PACKAGE,
            packageExists = true,
            enabled = true,
            sourceReadable = {
                sourceChecked = true
                true
            },
            hasLauncherActivity = {
                launcherChecked = true
                true
            },
        )

        assertFalse(eligible)
        assertFalse(sourceChecked)
        assertFalse(launcherChecked)
    }

    private fun eligible(
        candidatePackageName: String = "com.example.source",
        packageExists: Boolean = true,
        enabled: Boolean = true,
        sourceReadable: Boolean = true,
        hasLauncherActivity: Boolean = true,
    ): Boolean = isCloneableAppEligible(
        hostPackageName = HOST_PACKAGE,
        candidatePackageName = candidatePackageName,
        packageExists = packageExists,
        enabled = enabled,
        sourceReadable = { sourceReadable },
        hasLauncherActivity = { hasLauncherActivity },
    )

    private companion object {
        const val HOST_PACKAGE = "org.apptwin"
    }
}
