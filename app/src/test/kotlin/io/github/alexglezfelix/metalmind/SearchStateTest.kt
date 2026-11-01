package io.github.alexglezfelix.metalmind

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import io.github.alexglezfelix.metalmind.core.SearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchStateTest {
    private fun state(vararg ids: String) =
        SearchState(mutableStateOf(TextFieldValue()), mutableStateOf(null), mutableStateOf(emptyList())).apply {
            updateHits(ids.map { SearchHit(it, "$it.md", it, "") })
        }

    @Test
    fun `en un panel, abrir desde la lista no marca y Enter sigue abriendo el primero`() {
        val state = state("a", "b", "c")
        state.show("c", mark = false)
        state.back()
        assertNull(state.selected)
        assertEquals("a", state.submitTarget())
    }

    @Test
    fun `con dos paneles, la nota abierta queda marcada`() {
        val state = state("a", "b", "c")
        state.show("b", mark = true)
        assertEquals("b", state.selected)
        assertEquals("b", state.submitTarget())
    }

    @Test
    fun `las flechas marcan y con dos paneles también abren a la derecha`() {
        val state = state("a", "b", "c")
        state.move(1, showRight = false)
        state.move(1, showRight = false)
        assertEquals("b", state.selected)
        assertNull(state.openNote)

        state.move(1, showRight = true)
        assertEquals("c", state.openNote)
        assertEquals(listOf("c"), state.opened)
    }

    @Test
    fun `escribir otra búsqueda quita la marca`() {
        val state = state("a", "b")
        state.move(1, showRight = false)
        state.move(1, showRight = false)
        state.edit(TextFieldValue("otra"))
        assertNull(state.selected)
        assertEquals("a", state.submitTarget())
    }

    @Test
    fun `atrás vuelve de nota en nota y avisa cuando no queda ninguna`() {
        val state = state("a")
        state.show("a", mark = true)
        assertEquals(true, state.back())
        assertNull(state.openNote)
        assertEquals(false, state.back())
    }
}
