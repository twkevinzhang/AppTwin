package org.apptwin.ui

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.MainActivity
import org.apptwin.MainDestination
import org.apptwin.MainViewModel
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.GroupAppState
import org.apptwin.operations.FileOperationRecordStore
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Opt-in physical-device proof of the complete App picker to ready-state user journey. */
@RunWith(AndroidJUnit4::class)
class ClonePickerAutoLaunchE2eTest {
    private val notificationPermissionRule = object : ExternalResource() {
        override fun before() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
    }

    private val activityRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(notificationPermissionRule)
        .around(activityRule)

    private val composeRule
        get() = activityRule

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = instrumentation.targetContext

    @Test
    fun pickerSelectionAutomaticallyLaunchesCloneAndReturnsReady() = runBlocking {
        assumeTrue(
            "picker auto-launch E2E is opt-in; pass -e $OPT_IN_ARGUMENT 1",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "1",
        )
        assertTrue("self-owned fixture must be installed", isFixtureInstalled())
        val imported = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "fixture revision must activate: $imported",
            imported is RevisionImportResult.Activated ||
                imported is RevisionImportResult.AlreadyCurrent,
        )

        val operations = AndroidMainOperations(context.applicationContext as Application)
        val groupStore = FileGroupStore(context)
        val operationStore = FileOperationRecordStore(context)
        val groupName = "Picker auto-launch E2E ${System.currentTimeMillis()}"
        var createdGroupId: String? = null

        try {
            completeOnboardingIfNeeded()
            val viewModel = ViewModelProvider(composeRule.activity)[MainViewModel::class.java]
            viewModel.navigate(MainDestination.HOME)
            viewModel.createGroup(groupName)
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
                viewModel.uiState.groups.any { it.name == groupName }
            }
            val groupId = viewModel.uiState.groups.single { it.name == groupName }.groupId
            createdGroupId = groupId
            val group = requireNotNull(groupStore.find(groupId))
            val environmentId = requireNotNull(group.environmentBinding).internalId
            val hostTaskId = composeRule.activity.taskId

            awaitNode("home-add-app-$groupId")
            composeRule.onNodeWithTag("home-add-app-$groupId", useUnmergedTree = true)
                .performClick()
            awaitNode("app-picker-list")
            composeRule.onNodeWithTag("app-picker-list", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("app-picker-$FIXTURE_PACKAGE"))
            composeRule.onNodeWithTag("app-picker-$FIXTURE_PACKAGE", useUnmergedTree = true)
                .performClick()

            awaitGuestLaunchCount(environmentId, expected = "1")
            awaitExactCloneInForeground(environmentId)
            bringAppTwinToFront(hostTaskId)

            val tileTag = "home-app-tile-$groupId:$FIXTURE_PACKAGE"
            val statusTileTag = "group-app-tile-$groupId:$FIXTURE_PACKAGE"
            awaitNode(tileTag)
            awaitReadyLabel(statusTileTag)
            composeRule.onNodeWithTag(statusTileTag)
                .assertIsDisplayed()
                .assertTextContains("可使用")
            assertCloneState(groupStore, groupId, GroupAppState.ENABLED)
            assertNoActiveOperation(operationStore, groupId)
        } finally {
            createdGroupId?.let { groupId ->
                assertNotNull(
                    "dedicated picker E2E Group cleanup must succeed",
                    operations.deleteGroup(groupId),
                )
                assertTrue(
                    "dedicated picker E2E Group must be absent after cleanup",
                    groupStore.find(groupId) == null,
                )
            }
        }
    }

    private fun completeOnboardingIfNeeded() {
        val onboarding = composeRule.onAllNodes(hasTestTag("complete-onboarding"))
            .fetchSemanticsNodes()
        if (onboarding.isNotEmpty()) {
            composeRule.onNodeWithTag("complete-onboarding").performClick()
        }
    }

    private fun awaitNode(tag: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1
            }.getOrDefault(false)
        }
    }

    private fun awaitReadyLabel(tileTag: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithTag(tileTag).assertTextContains("可使用")
            }.isSuccess
        }
    }

    private fun assertCloneState(
        store: FileGroupStore,
        groupId: String,
        expected: GroupAppState,
    ) {
        val actual = store.find(groupId)?.apps
            ?.singleOrNull { it.packageName == FIXTURE_PACKAGE }
            ?.state
        assertTrue("clone state must be $expected but was $actual", actual == expected)
    }

    private fun assertNoActiveOperation(
        store: FileOperationRecordStore,
        groupId: String,
    ) {
        val active = store.listPending().filter { operation ->
            operation.target.spaceId == groupId &&
                operation.target.packageName == FIXTURE_PACKAGE
        }
        assertTrue("exact clone must not retain active operations: $active", active.isEmpty())
    }

    private fun awaitGuestLaunchCount(environmentId: Int, expected: String) {
        val file = File(
            VEnvironment.getDataUserPackageDirectory(environmentId, FIXTURE_PACKAGE),
            "files/$LAUNCH_COUNT_FILE",
        )
        var actual: String? = null
        repeat(POLL_ATTEMPTS) {
            actual = file.takeIf(File::isFile)?.readText()?.trim()
            if (actual == expected) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("fixture launch count did not reach $expected (actual=$actual)")
    }

    @Suppress("DEPRECATION")
    private fun awaitExactCloneInForeground(environmentId: Int) {
        var actualPackage: String? = null
        var actualEnvironmentId: Int? = null
        repeat(POLL_ATTEMPTS) {
            val physicalTaskId = context.getSystemService(ActivityManager::class.java)
                .getRunningTasks(1)
                .firstOrNull()
                ?.id
            val virtualTask = physicalTaskId?.let { VActivityManager.get().getTaskInfo(it) }
            actualPackage = virtualTask?.topActivity?.packageName
            actualEnvironmentId = virtualTask?.userId
            if (actualPackage == FIXTURE_PACKAGE && actualEnvironmentId == environmentId) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error(
            "exact fixture clone did not remain foreground: " +
                "expectedPackage=$FIXTURE_PACKAGE expectedEnvironmentId=$environmentId " +
                "actualPackage=$actualPackage actualEnvironmentId=$actualEnvironmentId",
        )
    }

    @Suppress("DEPRECATION")
    private fun bringAppTwinToFront(taskId: Int) {
        context.getSystemService(ActivityManager::class.java)
            .moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
    }

    private fun isFixtureInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0)
    }.isSuccess

    private companion object {
        const val OPT_IN_ARGUMENT = "clonePickerAutoLaunchE2e"
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
        const val LAUNCH_COUNT_FILE = "launch-count.txt"
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 100L
        const val UI_TIMEOUT_MILLIS = 15_000L
    }
}
