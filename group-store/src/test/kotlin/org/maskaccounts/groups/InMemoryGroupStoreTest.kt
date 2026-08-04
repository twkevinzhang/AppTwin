package org.maskaccounts.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryGroupStoreTest {
    @Test
    fun `a package can be added only once inside one group`() {
        val store = store()
        val group = store.create("工作", 100)
        store.addApp(group.id, LINE, 200)

        assertThrows(IllegalArgumentException::class.java) {
            store.addApp(group.id, LINE, 300)
        }
        assertEquals(listOf(LINE), store.find(group.id)?.apps?.map(GroupApp::packageName))
    }

    @Test
    fun `the same package can exist in separate groups`() {
        val ids = ArrayDeque(listOf(ID_1, ID_2))
        val store = InMemoryGroupStore(ids::removeFirst)
        val work = store.create("工作", 100)
        val personal = store.create("私人", 200)

        store.addApp(work.id, LINE, 300)
        store.addApp(personal.id, LINE, 400)

        assertTrue(store.find(work.id)!!.contains(LINE))
        assertTrue(store.find(personal.id)!!.contains(LINE))
    }

    @Test
    fun `rename trims name and runtime state is persisted`() {
        val store = store()
        val group = store.create("預設群組", 100)

        store.rename(group.id, "  工作帳號  ")
        val preparing = store.updateRuntimeState(group.id, GroupRuntimeState.PREPARING)

        assertEquals("工作帳號", preparing?.name)
        assertEquals(GroupRuntimeState.PREPARING, preparing?.runtimeState)
    }

    @Test
    fun `blank names are rejected`() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.create("  ", 100) }
        val group = store.create("工作", 100)
        assertThrows(IllegalArgumentException::class.java) { store.rename(group.id, " ") }
    }

    @Test
    fun `deleting one group leaves other groups intact`() {
        val ids = ArrayDeque(listOf(ID_1, ID_2))
        val store = InMemoryGroupStore(ids::removeFirst)
        val first = store.create("工作", 100)
        val second = store.create("私人", 200)

        assertTrue(store.delete(first.id))
        assertFalse(store.delete(first.id))
        assertNull(store.find(first.id))
        assertEquals(listOf(second), store.listAll())
    }

    private fun store() = InMemoryGroupStore { ID_1 }

    private companion object {
        const val ID_1 = "00000000-0000-0000-0000-000000000001"
        const val ID_2 = "00000000-0000-0000-0000-000000000002"
        const val LINE = "jp.naver.line.android"
    }
}
