package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingNoticesTest {
    private class FakeStore : NoticeStore {
        var saved: Set<String> = emptySet()

        override fun load() = saved

        override fun save(notices: Set<String>) {
            saved = notices
        }
    }

    @Test
    fun `un aviso se entrega una sola vez`() {
        val notices = PendingNotices(FakeStore())
        notices.add("Audio en recordings/a.m4a")
        assertEquals(setOf("Audio en recordings/a.m4a"), notices.items.value)
        assertEquals(setOf("Audio en recordings/a.m4a"), notices.take())
        assertTrue(notices.items.value.isEmpty())
        assertTrue(notices.take().isEmpty())
    }

    @Test
    fun `sobrevive a que muera el proceso hasta que se entrega`() {
        val store = FakeStore()
        PendingNotices(store).add("Audio en recordings/a.m4a")
        val next = PendingNotices(store)
        assertEquals(setOf("Audio en recordings/a.m4a"), next.take())
        assertTrue(PendingNotices(store).items.value.isEmpty())
    }

    @Test
    fun `varios avisos esperan juntos`() {
        val notices = PendingNotices(FakeStore())
        notices.add("a")
        notices.add("b")
        assertEquals(setOf("a", "b"), notices.take())
    }
}
