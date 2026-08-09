package org.apptwin.runtime

import android.accounts.Account
import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.system.Os
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.client.ipc.VNotificationManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VUserManager
import com.lody.virtual.os.VirtualExternalStorageLayout
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityStatus
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.usecases.GmsLifecycleResult
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.microg.artifact.PinnedMicrogRelease
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * ASUS acceptance for the per-Group microG compatibility layer.
 *
 * This test is intentionally opt-in because it installs the pinned microG asset and deletes all
 * Group data it creates. Generic connectedAndroidTest runs skip unless the harness passes
 * `-e microgFixturePhase 1`.
 */
@RunWith(AndroidJUnit4::class)
class MicrogGroupCompatibilityE2eTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun operations() =
        AndroidMainOperations(context.applicationContext as Application)

    @Test
    fun phaseOneExercisesTrustedInstallIsolationDisableResetAndUserReuse() = runBlocking {
        assumePhaseOne()
        val initialOperations = operations()
        val store = FileGroupStore(context)
        val initial = store.loadSnapshot()
        assertTrue("microG fixture requires clean Group metadata", initial.groups.isEmpty())
        assertTrue("microG fixture requires readable Group metadata", initial.issues.isEmpty())
        assertHostFixtureReady()
        assertFixtureRevisionReady()

        val groupA = initialOperations.createGroup("microG A")
        val groupB = initialOperations.createGroup("microG B")
        val groupAWithFixture = initialOperations.addAppToGroup(groupA.id, FIXTURE_PACKAGE)
        val groupBWithFixture = initialOperations.addAppToGroup(groupB.id, FIXTURE_PACKAGE)
        val userA = groupAWithFixture.environmentId()
        val userB = groupBWithFixture.environmentId()
        assertTrue("Group bindings must be positive and isolated", userA > 0 && userB > 0 && userA != userB)

        initialOperations.grantGmsConsent(groupA.id)
        assertLifecycleCompleted(initialOperations.enableGms(groupA.id), "enable A")
        assertTrue(VirtualCore.get().isAppInstalledAsUser(userA, GMS_PACKAGE))
        assertTrue(VirtualCore.get().isAppInstalledAsUser(userA, COMPANION_PACKAGE))
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userB, GMS_PACKAGE))
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userB, COMPANION_PACKAGE))
        assertEquals(
            PinnedMicrogRelease.VERSION_CODE,
            packageVersion(requireNotNull(VPackageManager.get().getPackageInfo(GMS_PACKAGE, 0, userA))),
        )
        assertEquals(
            PinnedMicrogRelease.COMPANION_VERSION_CODE,
            packageVersion(
                requireNotNull(VPackageManager.get().getPackageInfo(COMPANION_PACKAGE, 0, userA)),
            ),
        )
        assertNull(VPackageManager.get().getPackageInfo(GMS_PACKAGE, 0, userB))
        assertNull(VPackageManager.get().getPackageInfo(COMPANION_PACKAGE, 0, userB))
        assertTrustedSignatures(
            userId = userA,
            packageName = GMS_PACKAGE,
            source = materializedMicrogSource(),
        )
        assertTrustedSignatures(
            userId = userA,
            packageName = COMPANION_PACKAGE,
            source = materializedCompanionSource(),
        )
        assertGenericGmsInstallRejected()

        assertStarted(initialOperations.launchGroupApp(groupAWithFixture.fixtureItem()))
        assertStarted(initialOperations.launchGroupApp(groupBWithFixture.fixtureItem()))
        val aProbe = awaitProbeResults(userA)
        val bProbe = awaitProbeResults(userB)
        assertEquals("LOCAL_PASS", aProbe.status(PLAY_SERVICES_AVAILABILITY))
        assertTrue(aProbe.evidence(PLAY_SERVICES_AVAILABILITY).endsWith("=0"))
        assertEquals("LOCAL_ERROR", bProbe.status(PLAY_SERVICES_AVAILABILITY))
        assertFalse(bProbe.evidence(PLAY_SERVICES_AVAILABILITY).endsWith("=0"))
        assertEquals("EXTERNAL_UNTESTED", aProbe.status("FCM_LOCAL_CONTRACT"))
        assertEquals("UNSUPPORTED", aProbe.status("PLAY_BILLING"))
        assertEquals("UNSUPPORTED", aProbe.status("PLAY_INTEGRITY"))
        assertTrue("fixture sentinels must be isolated", aProbe.sentinel != bProbe.sentinel)
        assertProductClaimBoundary(initialOperations, groupA.id, groupB.id)

        val gmsSentinel = gmsSentinelFile(userA)
        writePrivateSentinel(gmsSentinel)
        val accountFixture = addOwnedAccountAndToken(userA, "before-reset")
        VNotificationManager.get().addNotification(
            NOTIFICATION_ID,
            NOTIFICATION_TAG,
            GMS_PACKAGE,
            userA,
        )
        assertTrue(VirtualCore.get().hasTrustedGmsBackgroundStateForUser(userA))

        assertLifecycleCompleted(initialOperations.disableGms(groupA.id), "disable A")
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userA, GMS_PACKAGE))
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userA, COMPANION_PACKAGE))
        assertTrue("disable must preserve GmsCore private state", gmsSentinel.isFile)
        assertFalse("disable must clear jobs, notifications and pending ownership",
            VirtualCore.get().hasTrustedGmsBackgroundStateForUser(userA))
        assertLifecycleCompleted(initialOperations.enableGms(groupA.id), "re-enable A")
        assertTrue("re-enable must retain the existing private state", gmsSentinel.isFile)
        assertEquals(
            1,
            VAccountManager.get().remote.getAccounts(userA, accountFixture.account.type).size,
        )
        assertNotNull(
            VAccountManager.get().remote.peekAuthToken(
                userA,
                accountFixture.account,
                accountFixture.tokenType,
            ),
        )

        assertLifecycleEventuallyCompleted(
            initial = initialOperations.resetGms(groupA.id, reenable = false),
            action = "reset A",
            retry = { initialOperations.resetGms(groupA.id, reenable = false) },
            diagnostic = { resetDiagnostic(userA, accountFixture.account.type) },
        )
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userA, GMS_PACKAGE))
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userA, COMPANION_PACKAGE))
        assertTrue("reset must remove every compatibility package private directory",
            compatibilityPrivateDirs(userA).none(File::exists))
        assertTrue(
            "reset must remove package-owned accounts",
            VAccountManager.get().remote.getAccounts(userA, accountFixture.account.type).isEmpty(),
        )
        assertNull(
            VAccountManager.get().remote.peekAuthToken(
                userA,
                accountFixture.account,
                accountFixture.tokenType,
            ),
        )
        assertFalse(VirtualCore.get().hasTrustedGmsBackgroundStateForUser(userA))
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userB, GMS_PACKAGE))
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userB, COMPANION_PACKAGE))
        assertEquals(bProbe.sentinel, awaitProbeResults(userB).sentinel)

        // Recreate sensitive package-owned state so Group deletion, not reset, owns the next gate.
        assertLifecycleCompleted(initialOperations.enableGms(groupA.id), "enable A before delete")
        val deleteAccount = addOwnedAccountAndToken(userA, "before-delete")
        writePrivateSentinel(gmsSentinelFile(userA))
        VNotificationManager.get().addNotification(
            NOTIFICATION_ID + 1,
            NOTIFICATION_TAG,
            GMS_PACKAGE,
            userA,
        )
        val foreignRoot = File(context.cacheDir, "group-delete-foreign-$userA").also {
            it.deleteRecursively()
            assertTrue(it.mkdirs())
        }
        val foreignSentinel = File(foreignRoot, "must-survive").also {
            assertTrue(it.createNewFile())
        }
        val userSymlink = File(VEnvironment.getUserSystemDirectory(userA), "external-sentinel-link")
        Os.symlink(foreignRoot.absolutePath, userSymlink.absolutePath)
        assertNotNull(initialOperations.deleteGroup(groupA.id))
        awaitVirtualUserAbsent(userA)
        assertTrue("Group cleanup must not follow a symlink outside the deleted user", foreignSentinel.isFile)
        assertFalse("Group cleanup must remove the user-owned symlink", userSymlink.exists())
        assertTrue(foreignRoot.deleteRecursively())
        assertTrue("deleted Group private directories must be absent",
            compatibilityPrivateDirs(userA).none(File::exists))
        assertTrue(
            "deleted Group accounts must be absent",
            VAccountManager.get().remote.getAccounts(userA, deleteAccount.account.type).isEmpty(),
        )

        // Restart the virtual system server without killing this instrumentation process. The
        // service reloads durable state, while the monotonic allocator gives the replacement a
        // fresh id. Forced same-id reuse is covered deterministically by local cleanup tests.
        restartVirtualSystemServer(userB)
        val restartedOperations = operations()
        restartedOperations.reconcileGms()
        val replacement = restartedOperations.createGroup("microG replacement")
        val replacementUser = replacement.environmentId()
        assertTrue("replacement must receive a fresh monotonic virtual user id", replacementUser > userB)
        assertFalse(VirtualCore.get().isAppInstalledAsUser(replacementUser, GMS_PACKAGE))
        assertFalse(VirtualCore.get().isAppInstalledAsUser(replacementUser, COMPANION_PACKAGE))
        assertFalse(VirtualCore.get().hasTrustedGmsBackgroundStateForUser(replacementUser))
        assertTrue(compatibilityPrivateDirs(replacementUser).none(File::exists))
        assertTrue(
            VAccountManager.get().remote.getAccounts(replacementUser, deleteAccount.account.type).isEmpty(),
        )
        assertTrue("reused virtual user id must start without old account/token state",
            VAccountManager.get().remote.getAccounts(replacementUser, null).isEmpty())
        assertEquals(bProbe.sentinel, awaitProbeResults(userB).sentinel)
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userB, GMS_PACKAGE))
        assertFalse(VirtualCore.get().isAppInstalledAsUser(userB, COMPANION_PACKAGE))

        assertTamperedPinnedSourceRejected(restartedOperations, replacement.id)

        assertNotNull(restartedOperations.deleteGroup(replacement.id))
        assertNotNull(restartedOperations.deleteGroup(groupB.id))
        assertTrue(FileGroupStore(context).loadSnapshot().groups.isEmpty())
    }

    private fun assertHostFixtureReady() {
        assertEquals(1L, packageVersion(context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0)))
    }

    private fun assertFixtureRevisionReady() {
        val result = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "host fixture revision must activate",
            result is RevisionImportResult.Activated || result is RevisionImportResult.AlreadyCurrent,
        )
    }

    private fun assertTrustedSignatures(userId: Int, packageName: String, source: File) {
        @Suppress("DEPRECATION")
        val virtual = requireNotNull(
            VPackageManager.get().getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES,
                userId,
            ),
        )
        @Suppress("DEPRECATION")
        val effective = requireNotNull(virtual.signatures).single().toByteArray()
        assertEquals(PinnedMicrogRelease.EXPOSED_CERTIFICATE_SHA256, sha256(effective))

        val archive = requireNotNull(
            context.packageManager.getPackageArchiveInfo(
                source.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ),
        )
        val real = requireNotNull(archive.signingInfo).apkContentsSigners.single().toByteArray()
        assertEquals(PinnedMicrogRelease.REAL_SIGNER_SHA256, sha256(real))
        assertTrue("effective compatibility signer must not replace artifact provenance",
            !effective.contentEquals(real))
    }

    private fun assertGenericGmsInstallRejected() {
        val result = VirtualCore.get().installPackage(materializedMicrogSource().absolutePath, 0)
        assertFalse("generic install path must never authorize the GMS signature override", result.isSuccess)
    }

    private suspend fun assertProductClaimBoundary(
        operations: AndroidMainOperations,
        groupA: String,
        groupB: String,
    ) {
        val product = operations.refreshSnapshot().gmsCompatibility
        assertEquals(GmsNetworkConsent.GRANTED, requireNotNull(product[groupA]).profile.networkConsent)
        assertEquals(GmsNetworkConsent.NOT_GRANTED, requireNotNull(product[groupB]).profile.networkConsent)
        product.values.forEach { state ->
            state.capabilities.forEach { capability ->
                if (capability.capability in setOf(
                        GmsCapability.PLAY_BILLING,
                        GmsCapability.PLAY_INTEGRITY,
                    )
                ) {
                    assertEquals(GmsCapabilityStatus.UNSUPPORTED, capability.status)
                } else {
                    assertTrue(
                        "ASUS fixture must not upgrade claims to a real external tier",
                        capability.status !in setOf(
                            GmsCapabilityStatus.REAL_EXTERNAL_VERIFIED,
                            GmsCapabilityStatus.THIRD_PARTY_APP_VERIFIED,
                        ),
                    )
                }
            }
        }
    }

    private fun addOwnedAccountAndToken(userId: Int, suffix: String): AccountFixture {
        val remote = VAccountManager.get().remote
        val authenticator = remote.getAuthenticatorTypes(userId)
            .firstOrNull { it.packageName == GMS_PACKAGE }
        assertNotNull("GmsCore must expose at least one virtual authenticator", authenticator)
        val account = Account("fixture-$suffix@apptwin.invalid", requireNotNull(authenticator).type)
        assertTrue(remote.addAccountExplicitly(userId, account, "fixture-password", Bundle()))
        val tokenType = "apptwin-e2e"
        remote.setAuthToken(userId, account, tokenType, "opaque-fixture-token")
        assertNotNull(remote.peekAuthToken(userId, account, tokenType))
        return AccountFixture(account, tokenType)
    }

    private suspend fun assertTamperedPinnedSourceRejected(
        operations: AndroidMainOperations,
        groupId: String,
    ) {
        operations.grantGmsConsent(groupId)
        val source = materializedMicrogSource()
        RandomAccessFile(source, "rw").use { file ->
            val offset = file.length() - 1
            file.seek(offset)
            val original = file.readByte()
            try {
                file.seek(offset)
                file.writeByte(original.toInt() xor 0x01)
                file.fd.sync()
                val result = operations.enableGms(groupId)
                assertTrue("tampered pinned artifact must fail terminally", result is GmsLifecycleResult.Rejected)
                assertEquals(
                    "ARTIFACT_APK_DIGEST_MISMATCH",
                    (result as GmsLifecycleResult.Rejected).failureCode,
                )
            } finally {
                file.seek(offset)
                file.writeByte(original.toInt())
                file.fd.sync()
            }
        }
    }

    private fun writePrivateSentinel(file: File) {
        val parent = requireNotNull(file.parentFile)
        assertTrue(parent.isDirectory || parent.mkdirs())
        FileOutputStream(file).use { output ->
            output.write("microg-private-state".toByteArray())
            output.fd.sync()
        }
    }

    private fun gmsSentinelFile(userId: Int) =
        File(VEnvironment.getDataUserPackageDirectory(userId, GMS_PACKAGE), "files/e2e-state.bin")

    private fun gmsPrivateDirs(userId: Int): List<File> = listOf(
        File(VEnvironment.getUserSystemDirectory(userId), GMS_PACKAGE),
        File(VEnvironment.getDeUserSystemDirectory(userId), GMS_PACKAGE),
    ) + privateExternalDirectory(userId, GMS_PACKAGE)

    private fun companionPrivateDirs(userId: Int): List<File> = listOf(
        File(VEnvironment.getUserSystemDirectory(userId), COMPANION_PACKAGE),
        File(VEnvironment.getDeUserSystemDirectory(userId), COMPANION_PACKAGE),
    ) + privateExternalDirectory(userId, COMPANION_PACKAGE)

    private fun privateExternalDirectory(userId: Int, packageName: String): List<File> =
        context.getExternalFilesDir(null)?.let { externalRoot ->
            listOf(
                File(
                    VirtualExternalStorageLayout.privateStorageForUser(externalRoot, userId),
                    packageName,
                ),
            )
        }.orEmpty()

    private fun compatibilityPrivateDirs(userId: Int): List<File> =
        gmsPrivateDirs(userId) + companionPrivateDirs(userId)

    private fun materializedMicrogSource() = File(
        context.filesDir,
        "microg-artifact-source/${PinnedMicrogRelease.APK_FILE_NAME}",
    ).also { assertTrue("pinned microG source must be materialized", it.isFile) }

    private fun materializedCompanionSource() = File(
        context.filesDir,
        "microg-artifact-source/${PinnedMicrogRelease.COMPANION_APK_FILE_NAME}",
    ).also { assertTrue("pinned microG Companion source must be materialized", it.isFile) }

    private fun awaitProbeResults(userId: Int): FixtureProbeSnapshot {
        repeat(POLL_ATTEMPTS) {
            readProbeResults(userId)?.takeIf { snapshot ->
                snapshot.status("PLAY_INTEGRITY") != null
            }?.let { return it }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("virtual fixture probes did not reach a terminal local result")
    }

    private fun readProbeResults(userId: Int): FixtureProbeSnapshot? {
        val preferences = File(
            VEnvironment.getDataUserPackageDirectory(userId, FIXTURE_PACKAGE),
            "shared_prefs/gms_capability_fixture.xml",
        )
        if (!preferences.isFile) return null
        val values = linkedMapOf<String, String>()
        preferences.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, Charsets.UTF_8.name())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "string") {
                    val name = parser.getAttributeValue(null, "name")
                    values[name] = parser.nextText()
                }
                event = parser.next()
            }
        }
        val sentinel = values["group_sentinel"] ?: return null
        return FixtureProbeSnapshot(sentinel, values)
    }

    private fun awaitVirtualUserAbsent(userId: Int) {
        repeat(POLL_ATTEMPTS) {
            if (VUserManager.get().getUserInfo(userId) == null) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("deleted virtual user remained visible")
    }

    private fun restartVirtualSystemServer(survivingUserId: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pidDescriptor = instrumentation.uiAutomation.executeShellCommand(
            "pidof $VIRTUAL_SYSTEM_PROCESS",
        )
        val pid = pidDescriptor.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                reader.readText().trim().toIntOrNull()
            }
        }
        assertNotNull("virtual system-server process must be running", pid)
        instrumentation.uiAutomation.executeShellCommand(
            "run-as org.apptwin kill -9 ${requireNotNull(pid)}",
        ).close()
        repeat(POLL_ATTEMPTS) {
            val surviving = runCatching { VUserManager.get().getUserInfo(survivingUserId) }.getOrNull()
            if (surviving != null) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("virtual system-server did not recover durable user state")
    }

    private fun Group.fixtureItem(): GroupAppItem {
        val app = requireNotNull(apps.singleOrNull { it.packageName == FIXTURE_PACKAGE })
        return GroupAppItem(
            groupId = id,
            groupName = name,
            groupHealth = health,
            app = app,
            appLabel = "AppTwin GMS Capability Fixture",
            versionName = "1.0",
            sourceInstalled = true,
            launchStatus = "",
        )
    }

    private fun Group.environmentId(): Int = requireNotNull(environmentBinding).internalId

    private fun assertStarted(result: RuntimeLaunchResult) {
        assertTrue("fixture must start inside the virtual user: $result", result is RuntimeLaunchResult.Started)
    }

    private fun assertLifecycleCompleted(result: GmsLifecycleResult, action: String) {
        assertTrue(
            "$action did not reach a business terminal state: $result",
            result is GmsLifecycleResult.Completed || result is GmsLifecycleResult.AlreadySatisfied,
        )
    }

    private suspend fun assertLifecycleEventuallyCompleted(
        initial: GmsLifecycleResult,
        action: String,
        retry: suspend () -> GmsLifecycleResult,
        diagnostic: () -> String,
    ) {
        var result = initial
        repeat(LIFECYCLE_RETRY_ATTEMPTS) {
            if (result is GmsLifecycleResult.Completed ||
                result is GmsLifecycleResult.AlreadySatisfied
            ) {
                return
            }
            assertTrue(
                "$action failed terminally instead of scheduling an idempotent retry: $result",
                result is GmsLifecycleResult.RetryScheduled,
            )
            Thread.sleep(LIFECYCLE_RETRY_INTERVAL_MILLIS)
            result = retry()
        }
        assertLifecycleCompleted(result, "$action; ${diagnostic()}")
    }

    private fun resetDiagnostic(userId: Int, ownedAccountType: String): String {
        val gmsInstalled = VirtualCore.get().isAppInstalledAsUser(userId, GMS_PACKAGE)
        val companionInstalled = VirtualCore.get().isAppInstalledAsUser(userId, COMPANION_PACKAGE)
        val gmsDirectories = gmsPrivateDirs(userId).count(File::exists)
        val companionDirectories = companionPrivateDirs(userId).count(File::exists)
        val background = VirtualCore.get().hasTrustedGmsBackgroundStateForUser(userId)
        val ownedAccounts = VAccountManager.get().remote.getAccounts(userId, ownedAccountType).size
        return "gmsInstalled=$gmsInstalled companionInstalled=$companionInstalled " +
            "gmsDirectories=$gmsDirectories companionDirectories=$companionDirectories " +
            "background=$background ownedAccounts=$ownedAccounts"
    }

    private fun packageVersion(info: PackageInfo): Long = info.longVersionCode

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun assumePhaseOne() {
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
        assumeTrue(
            "microG ASUS fixture is opt-in; pass -e $PHASE_ARGUMENT 1",
            phase == "1",
        )
    }

    private data class FixtureProbeSnapshot(
        val sentinel: String,
        val values: Map<String, String>,
    ) {
        fun status(id: String): String? = values["$id.status"]
        fun evidence(id: String): String = requireNotNull(values["$id.evidence"])
    }

    private data class AccountFixture(val account: Account, val tokenType: String)

    private companion object {
        const val PHASE_ARGUMENT = "microgFixturePhase"
        const val FIXTURE_PACKAGE = "org.apptwin.gms.fixture"
        const val GMS_PACKAGE = "com.google.android.gms"
        const val COMPANION_PACKAGE = "com.android.vending"
        const val VIRTUAL_SYSTEM_PROCESS = "org.apptwin:x"
        const val PLAY_SERVICES_AVAILABILITY = "PLAY_SERVICES_AVAILABILITY"
        const val NOTIFICATION_ID = 29_031
        const val NOTIFICATION_TAG = "apptwin-gms-fixture"
        const val POLL_ATTEMPTS = 150
        const val POLL_INTERVAL_MILLIS = 100L
        const val LIFECYCLE_RETRY_ATTEMPTS = 4
        const val LIFECYCLE_RETRY_INTERVAL_MILLIS = 150L
    }
}
