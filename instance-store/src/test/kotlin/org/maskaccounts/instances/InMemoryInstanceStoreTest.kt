package org.maskaccounts.instances

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryInstanceStoreTest {
    @Test
    fun `multiple instances of one package remain distinct`() {
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
            ),
        )
        val store = InMemoryInstanceStore(ids::removeFirst)

        val first = store.create(PACKAGE, "LINE 1", 100)
        val second = store.create(PACKAGE, "LINE 2", 200)

        assertEquals(2, store.list(PACKAGE).size)
        assertEquals(first, store.find(first.id))
        assertEquals(second, store.find(second.id))
    }

    @Test
    fun `instances are filtered by package`() {
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
            ),
        )
        val store = InMemoryInstanceStore(ids::removeFirst)
        store.create(PACKAGE, "LINE 1", 100)
        store.create("com.discord", "Discord 1", 200)

        assertEquals(listOf("LINE 1"), store.list(PACKAGE).map(VirtualInstance::displayName))
        assertNull(store.find("00000000-0000-0000-0000-000000000099"))
    }

    @Test
    fun `blank display name is rejected`() {
        val store = InMemoryInstanceStore { "00000000-0000-0000-0000-000000000001" }
        assertThrows(IllegalArgumentException::class.java) {
            store.create(PACKAGE, "  ", 100)
        }
    }

    @Test
    fun `list all returns instances across packages in stable creation order`() {
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000003",
            ),
        )
        val store = InMemoryInstanceStore(ids::removeFirst)
        store.create(PACKAGE, "LINE 2", 200)
        store.create("com.discord", "Discord 1", 100)
        store.create(PACKAGE, "LINE 1", 100)

        assertEquals(
            listOf("Discord 1", "LINE 1", "LINE 2"),
            store.listAll().map(VirtualInstance::displayName),
        )
    }

    @Test
    fun `rename trims display name and preserves instance identity`() {
        val store = InMemoryInstanceStore { "00000000-0000-0000-0000-000000000001" }
        val original = store.create(PACKAGE, "LINE 1", 100)

        val renamed = store.rename(original.id, "  工作帳號  ")

        assertEquals(original.copy(displayName = "工作帳號"), renamed)
        assertEquals(renamed, store.find(original.id))
    }

    @Test
    fun `blank rename is rejected without changing existing instance`() {
        val store = InMemoryInstanceStore { "00000000-0000-0000-0000-000000000001" }
        val original = store.create(PACKAGE, "LINE 1", 100)

        assertThrows(IllegalArgumentException::class.java) {
            store.rename(original.id, "  ")
        }

        assertEquals(original, store.find(original.id))
    }

    @Test
    fun `rename and delete report a missing instance without changing others`() {
        val store = InMemoryInstanceStore { "00000000-0000-0000-0000-000000000001" }
        val existing = store.create(PACKAGE, "LINE 1", 100)
        val missingId = "00000000-0000-0000-0000-000000000099"

        assertNull(store.rename(missingId, "New name"))
        assertFalse(store.delete(missingId))
        assertEquals(existing, store.find(existing.id))
    }

    @Test
    fun `delete removes only the selected instance`() {
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
            ),
        )
        val store = InMemoryInstanceStore(ids::removeFirst)
        val first = store.create(PACKAGE, "LINE 1", 100)
        val second = store.create(PACKAGE, "LINE 2", 200)

        assertTrue(store.delete(first.id))

        assertNull(store.find(first.id))
        assertEquals(second, store.find(second.id))
        assertEquals(listOf(second), store.listAll())
    }

    private companion object {
        const val PACKAGE = "jp.naver.line.android"
    }
}
