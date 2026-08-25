package org.apptwin.runtime

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRecoveryPolicyTest {
    private val bootAction = "android.intent.action.BOOT_COMPLETED"

    @Test
    fun `boot starts only for enabled durable profile and allowed recovery`() {
        assertTrue(BootRecoveryPolicy.shouldStart(bootAction, bootAction, true, true))
        assertFalse(BootRecoveryPolicy.shouldStart(bootAction, bootAction, false, true))
        assertFalse(BootRecoveryPolicy.shouldStart(bootAction, bootAction, true, false))
        assertFalse(BootRecoveryPolicy.shouldStart("other", bootAction, true, true))
        assertFalse(BootRecoveryPolicy.shouldStart(null, bootAction, true, true))
    }

    @Test
    fun `receiver reads file profiles and uses non-visible recovery entry`() {
        val source = File("src/main/java/org/apptwin/runtime/BootReceiver.kt").readText()

        assertTrue(source.contains("DaemonWorkloadAuthorization"))
        assertTrue(source.contains("loadEnabledVirtualUserIds"))
        assertTrue(source.contains("DaemonService.allowsAutomaticRecovery"))
        assertTrue(source.contains("DaemonService.startupForBackgroundRecovery"))
        assertFalse(source.contains("DaemonService.startup(context"))
    }

    @Test
    fun `enabled profiles resolve to an exact sorted virtual user allowlist`() {
        val resolved = DaemonWorkloadAuthorization.resolveEnabledVirtualUserIds(
            enabledGroupIds = setOf("group-b", "group-a", "group-c"),
            bindingsByGroupId = mapOf(
                "group-a" to 3,
                "group-b" to 1,
                "group-c" to 3,
            ),
        )

        assertTrue(resolved!!.contentEquals(intArrayOf(1, 3)))
    }

    @Test
    fun `partial or host-user mappings fail closed instead of replacing runtime authority`() {
        assertNull(
            DaemonWorkloadAuthorization.resolveEnabledVirtualUserIds(
                enabledGroupIds = setOf("group-a", "missing"),
                bindingsByGroupId = mapOf("group-a" to 1),
            ),
        )
        assertNull(
            DaemonWorkloadAuthorization.resolveEnabledVirtualUserIds(
                enabledGroupIds = setOf("host"),
                bindingsByGroupId = mapOf("host" to 0),
            ),
        )
    }

    @Test
    fun `no enabled profiles authoritatively resolves to an empty allowlist`() {
        val resolved = DaemonWorkloadAuthorization.resolveEnabledVirtualUserIds(
            enabledGroupIds = emptySet(),
            bindingsByGroupId = mapOf("disabled" to 1),
        )

        assertTrue(resolved!!.isEmpty())
    }

    @Test
    fun `existing non-directory durable root is not authoritative empty state`() {
        val parent = Files.createTempDirectory("apptwin-daemon-auth").toFile()
        try {
            val invalidRoot = File(parent, "groups").apply { writeText("corrupt") }

            assertFalse(DaemonWorkloadAuthorization.isListableDurableRoot(invalidRoot))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `missing or listable durable root can authoritatively represent empty state`() {
        val parent = Files.createTempDirectory("apptwin-daemon-auth").toFile()
        try {
            val missingRoot = File(parent, "missing")
            val directoryRoot = File(parent, "groups").apply { check(mkdir()) }

            assertTrue(DaemonWorkloadAuthorization.isListableDurableRoot(missingRoot))
            assertTrue(DaemonWorkloadAuthorization.isListableDurableRoot(directoryRoot))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `boot receiver is private and listens only for completed boot`() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val receivers = document.getElementsByTagName("receiver")
        val receiver = (0 until receivers.length)
            .map { receivers.item(it) }
            .first { node ->
                node.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                    ".runtime.BootReceiver"
            }

        assertTrue(
            receiver.attributes.getNamedItemNS(androidNamespace, "exported").nodeValue == "false",
        )
        val actions = receiver.childNodes
        assertTrue(
            (0 until actions.length)
                .map { actions.item(it) }
                .flatMap { child ->
                    if (child.nodeName == "intent-filter") {
                        (0 until child.childNodes.length).map { child.childNodes.item(it) }
                    } else {
                        emptyList()
                    }
                }
                .any { action ->
                    action.nodeName == "action" &&
                        action.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                        "android.intent.action.BOOT_COMPLETED"
                },
        )
    }
}
