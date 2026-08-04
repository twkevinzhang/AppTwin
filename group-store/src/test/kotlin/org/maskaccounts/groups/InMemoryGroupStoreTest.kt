package org.maskaccounts.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryGroupStoreTest {
    @Test
    fun `a new Group is born healthy with one immutable environment`() {
        val store = InMemoryGroupStore()
        val group = store.create(ID_1, "工作", EnvironmentBinding(8), 100)

        assertEquals(GroupHealth.HEALTHY, group.health)
        assertEquals(EnvironmentBinding(8), group.environmentBinding)
        assertEquals(GoogleServicesState.NOT_PREPARED, group.googleServicesState)
    }

    @Test
    fun `two active Groups cannot own the same environment`() {
        val store = InMemoryGroupStore()
        store.create(ID_1, "工作", EnvironmentBinding(8), 100)

        assertThrows(IllegalStateException::class.java) {
            store.create(ID_2, "私人", EnvironmentBinding(8), 200)
        }
    }

    @Test
    fun `provisioning completes once and binding cannot be replaced`() {
        val store = InMemoryGroupStore()
        store.import(legacyGroup(ID_1, null))

        val completed = store.completeProvisioning(ID_1, EnvironmentBinding(9))

        assertEquals(GroupHealth.HEALTHY, completed?.health)
        assertEquals(EnvironmentBinding(9), completed?.environmentBinding)
        assertThrows(IllegalArgumentException::class.java) {
            store.completeProvisioning(ID_1, EnvironmentBinding(10))
        }
    }

    @Test
    fun `a package can be added only once inside one Group`() {
        val store = InMemoryGroupStore()
        val group = store.create(ID_1, "工作", EnvironmentBinding(8), 100)
        store.addApp(group.id, LINE, 200)

        assertThrows(IllegalArgumentException::class.java) {
            store.addApp(group.id, LINE, 300)
        }
    }

    @Test
    fun `the same package can exist in separate Groups`() {
        val store = InMemoryGroupStore()
        val work = store.create(ID_1, "工作", EnvironmentBinding(8), 100)
        val personal = store.create(ID_2, "私人", EnvironmentBinding(9), 200)

        store.addApp(work.id, LINE, 300)
        store.addApp(personal.id, LINE, 400)

        assertTrue(store.find(work.id)!!.contains(LINE))
        assertTrue(store.find(personal.id)!!.contains(LINE))
    }

    @Test
    fun `damaged Group cannot receive a new App`() {
        val store = InMemoryGroupStore()
        val group = store.create(ID_1, "工作", EnvironmentBinding(8), 100)
        store.updateHealth(group.id, GroupHealth.DAMAGED)

        assertThrows(IllegalArgumentException::class.java) {
            store.addApp(group.id, LINE, 200)
        }
    }

    @Test
    fun `deleting one Group leaves other Group intact`() {
        val store = InMemoryGroupStore()
        val first = store.create(ID_1, "工作", EnvironmentBinding(8), 100)
        val second = store.create(ID_2, "私人", EnvironmentBinding(9), 200)

        assertTrue(store.delete(first.id))
        assertFalse(store.delete(first.id))
        assertNull(store.find(first.id))
        assertEquals(listOf(second), store.listAll())
    }

    private fun legacyGroup(id: String, binding: EnvironmentBinding?) = Group(
        id = id,
        name = "舊群組",
        createdAtEpochMillis = 100,
        environmentBinding = binding,
        health = GroupHealth.PROVISIONING,
        schemaVersion = 1,
    )

    private companion object {
        const val ID_1 = "00000000-0000-0000-0000-000000000001"
        const val ID_2 = "00000000-0000-0000-0000-000000000002"
        const val LINE = "jp.naver.line.android"
    }
}
