package org.maskaccounts.instances

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    private companion object {
        const val PACKAGE = "jp.naver.line.android"
    }
}
