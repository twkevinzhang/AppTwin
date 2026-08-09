package org.apptwin.runtime

import android.accounts.Account
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.gms.usecases.GmsLifecycleResult
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Opt-in hostile guest test for raw virtual Binder calls that forge another Group's user id.
 *
 * The debug fixture deliberately calls the virtual Account, Package and Activity managers instead
 * of Android framework APIs. It records booleans only, so account passwords and tokens never leave
 * the isolated test state.
 */
@RunWith(AndroidJUnit4::class)
class CrossUserBinderIsolationE2eTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun groupBGuestCannotForgeGroupAUserIdAcrossRawBinderClients() = runBlocking {
        assumeOptIn()
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val initial = FileGroupStore(context).loadSnapshot()
        assertTrue("cross-user Binder fixture requires clean Group metadata", initial.groups.isEmpty())
        assertTrue("cross-user Binder fixture requires readable Group metadata", initial.issues.isEmpty())
        assertHostFixtureReady()
        assertFixtureRevisionReady()

        val groupA = operations.createGroup("Binder target A")
        val groupB = operations.createGroup("Binder attacker B")
        try {
            val groupAWithFixture = operations.addAppToGroup(groupA.id, FIXTURE_PACKAGE)
            val groupBWithFixture = operations.addAppToGroup(groupB.id, FIXTURE_PACKAGE)
            val userA = groupAWithFixture.environmentId()
            val userB = groupBWithFixture.environmentId()
            assertTrue("target and attacker must be distinct virtual users", userA > 0 && userB > 0 && userA != userB)

            operations.grantGmsConsent(groupA.id)
            assertLifecycleCompleted(operations.enableGms(groupA.id), "enable target A")

            // Install the debug fixture and its provider in both virtual users before the attack.
            assertStarted(operations.launchGroupApp(groupAWithFixture.fixtureItem()))
            assertStarted(operations.launchGroupApp(groupBWithFixture.fixtureItem()))

            val remoteAccounts = VAccountManager.get().remote
            val accountType = requireNotNull(
                remoteAccounts.getAuthenticatorTypes(userA)
                    .firstOrNull { it.packageName == GMS_PACKAGE },
            ).type
            val targetAccount = Account("target@apptwin.invalid", accountType)
            val selfAccount = Account("attacker@apptwin.invalid", accountType)
            assertTrue(remoteAccounts.addAccountExplicitly(userA, targetAccount, TARGET_PASSWORD, Bundle()))
            assertTrue(remoteAccounts.addAccountExplicitly(userB, selfAccount, SELF_PASSWORD, Bundle()))
            remoteAccounts.setAuthToken(userA, targetAccount, TOKEN_TYPE, TARGET_TOKEN)
            remoteAccounts.setAuthToken(userB, selfAccount, TOKEN_TYPE, SELF_TOKEN)

            // Control observations prove the management process can still address both users.
            assertEquals(TARGET_PASSWORD, remoteAccounts.getPassword(userA, targetAccount))
            assertEquals(TARGET_TOKEN, remoteAccounts.peekAuthToken(userA, targetAccount, TOKEN_TYPE))
            assertEquals(SELF_PASSWORD, remoteAccounts.getPassword(userB, selfAccount))
            assertEquals(SELF_TOKEN, remoteAccounts.peekAuthToken(userB, selfAccount, TOKEN_TYPE))
            assertNotNull(VPackageManager.get().getPackageInfo(GMS_PACKAGE, 0, userA))
            assertNotNull(VPackageManager.get().getPackageInfo(COMPANION_PACKAGE, 0, userA))
            assertNotNull(VPackageManager.get().getPackageInfo(FIXTURE_PACKAGE, 0, userB))
            assertNotNull(
                VPackageManager.get().getProviderInfo(
                    ComponentName(FIXTURE_PACKAGE, CROSS_USER_PROVIDER),
                    0,
                    userA,
                ),
            )

            writeAttackRequest(userA, userB, accountType, targetAccount.name, selfAccount.name)
            val maliciousLaunch = VirtualRuntimeController(context).installAndLaunch(
                groupBWithFixture,
                groupBWithFixture.fixtureApp(),
                MALICIOUS_ACTIVITY,
            )
            assertStarted(maliciousLaunch)

            val result = awaitAttackResult(userB)
            REQUIRED_TRUE_RESULTS.forEach { key ->
                assertEquals("malicious Binder assertion failed: $key", true, result[key])
            }

            // Re-read the target through the host after the attack: rejection must not corrupt it.
            assertEquals(TARGET_PASSWORD, remoteAccounts.getPassword(userA, targetAccount))
            assertEquals(TARGET_TOKEN, remoteAccounts.peekAuthToken(userA, targetAccount, TOKEN_TYPE))
            assertEquals(UPDATED_SELF_PASSWORD, remoteAccounts.getPassword(userB, selfAccount))
            assertEquals(UPDATED_SELF_TOKEN, remoteAccounts.peekAuthToken(userB, selfAccount, TOKEN_TYPE))
            assertNotNull(VPackageManager.get().getPackageInfo(GMS_PACKAGE, 0, userA))
            assertNotNull(VPackageManager.get().getPackageInfo(COMPANION_PACKAGE, 0, userA))
            assertTrue(
                "target Group fixture binding must remain visible",
                VirtualCore.get().getInstalledAppsAsUser(userA, 0)
                    .any { it.packageName == FIXTURE_PACKAGE },
            )
            assertTrue(
                "unprivileged same-Group binding must not expose trusted GMS",
                VPackageManager.get().getPackageInfo(GMS_PACKAGE, 0, userB) == null,
            )
        } finally {
            runCatching { operations.deleteGroup(groupA.id) }
            runCatching { operations.deleteGroup(groupB.id) }
        }
        assertTrue(FileGroupStore(context).loadSnapshot().groups.isEmpty())
    }

    private fun writeAttackRequest(
        targetUser: Int,
        selfUser: Int,
        accountType: String,
        targetAccount: String,
        selfAccount: String,
    ) {
        val file = File(
            VEnvironment.getDataUserPackageDirectory(selfUser, FIXTURE_PACKAGE),
            "shared_prefs/$REQUEST_FILE.xml",
        )
        assertTrue(requireNotNull(file.parentFile).isDirectory || requireNotNull(file.parentFile).mkdirs())
        FileOutputStream(file).use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, Charsets.UTF_8.name())
            serializer.startDocument(Charsets.UTF_8.name(), true)
            serializer.startTag(null, "map")
            serializer.intPreference(EXTRA_TARGET_USER, targetUser)
            serializer.intPreference(EXTRA_SELF_USER, selfUser)
            serializer.stringPreference(EXTRA_ACCOUNT_TYPE, accountType)
            serializer.stringPreference(EXTRA_TARGET_ACCOUNT, targetAccount)
            serializer.stringPreference(EXTRA_SELF_ACCOUNT, selfAccount)
            serializer.endTag(null, "map")
            serializer.endDocument()
            output.fd.sync()
        }
    }

    private fun org.xmlpull.v1.XmlSerializer.intPreference(name: String, value: Int) {
        startTag(null, "int")
        attribute(null, "name", name)
        attribute(null, "value", value.toString())
        endTag(null, "int")
    }

    private fun org.xmlpull.v1.XmlSerializer.stringPreference(name: String, value: String) {
        startTag(null, "string")
        attribute(null, "name", name)
        text(value)
        endTag(null, "string")
    }

    private fun awaitAttackResult(userId: Int): Map<String, Boolean> {
        repeat(POLL_ATTEMPTS) {
            readAttackResult(userId)?.takeIf { it[COMPLETE] == true }?.let { return it }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("malicious guest did not persist a terminal Binder result")
    }

    private fun readAttackResult(userId: Int): Map<String, Boolean>? {
        val file = File(
            VEnvironment.getDataUserPackageDirectory(userId, FIXTURE_PACKAGE),
            "shared_prefs/$RESULT_FILE.xml",
        )
        if (!file.isFile) return null
        val values = linkedMapOf<String, Boolean>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, Charsets.UTF_8.name())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "boolean") {
                    val name = parser.getAttributeValue(null, "name")
                    values[name] = parser.getAttributeValue(null, "value").toBooleanStrict()
                }
                event = parser.next()
            }
        }
        return values
    }

    private fun assertHostFixtureReady() {
        assertEquals(1L, context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0).longVersionCode)
    }

    private fun assertFixtureRevisionReady() {
        val result = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "host fixture revision must activate",
            result is RevisionImportResult.Activated || result is RevisionImportResult.AlreadyCurrent,
        )
    }

    private fun Group.fixtureItem(): GroupAppItem {
        val app = fixtureApp()
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

    private fun Group.fixtureApp() = requireNotNull(apps.singleOrNull { it.packageName == FIXTURE_PACKAGE })

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

    private fun assumeOptIn() {
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
        assumeTrue(
            "cross-user Binder ASUS fixture is opt-in; pass -e $PHASE_ARGUMENT 1",
            phase == "1",
        )
    }

    private companion object {
        const val PHASE_ARGUMENT = "crossUserBinderPhase"
        const val FIXTURE_PACKAGE = "org.apptwin.gms.fixture"
        const val GMS_PACKAGE = "com.google.android.gms"
        const val COMPANION_PACKAGE = "com.android.vending"
        const val MALICIOUS_ACTIVITY = "$FIXTURE_PACKAGE.MaliciousCrossUserActivity"
        const val CROSS_USER_PROVIDER = "$FIXTURE_PACKAGE.CrossUserFixtureProvider"
        const val REQUEST_FILE = "cross_user_request"
        const val RESULT_FILE = "cross_user_probe"
        const val COMPLETE = "complete"
        const val EXTRA_TARGET_USER = "target_user"
        const val EXTRA_SELF_USER = "self_user"
        const val EXTRA_ACCOUNT_TYPE = "account_type"
        const val EXTRA_TARGET_ACCOUNT = "target_account"
        const val EXTRA_SELF_ACCOUNT = "self_account"
        const val TOKEN_TYPE = "apptwin-malicious-e2e"
        const val TARGET_PASSWORD = "target-password"
        const val SELF_PASSWORD = "self-password"
        const val TARGET_TOKEN = "opaque-target-token"
        const val SELF_TOKEN = "opaque-self-token"
        const val UPDATED_SELF_PASSWORD = "updated-self-password"
        const val UPDATED_SELF_TOKEN = "updated-self-token"
        const val POLL_ATTEMPTS = 150
        const val POLL_INTERVAL_MILLIS = 100L

        val REQUIRED_TRUE_RESULTS = listOf(
            "cross_account_list_blocked",
            "cross_password_blocked",
            "cross_token_blocked",
            "cross_password_write_blocked",
            "cross_token_write_blocked",
            "cross_gms_pm_blocked",
            "cross_vending_pm_blocked",
            "cross_clear_target_blocked",
            "cross_uninstall_target_blocked",
            "cross_admin_mutation_blocked",
            "generic_trusted_bind_blocked",
            "cross_provider_blocked",
            "same_account_list_allowed",
            "same_password_allowed",
            "same_token_allowed",
            "same_password_write_allowed",
            "same_token_write_allowed",
            "same_pm_allowed",
            "same_provider_allowed",
            COMPLETE,
        )
    }
}
