package io.github.alexglezfelix.metalmind.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioAttachmentTest {
    @Test
    fun `el nombre es la fecha y la hora`() {
        assertEquals("2026-11-01-093005.m4a", AudioAttachment.fileName(LocalDateTime.of(2026, 11, 1, 9, 30, 5)))
        assertEquals("![[2026-11-01-093005.m4a]]", AudioAttachment.embed("2026-11-01-093005.m4a"))
    }

    @Test
    fun `solo se guardan los audios que siguen enlazados`() {
        val text = "Idea\n\n![[2026-11-01-093005.m4a]]"
        val plan = AudioAttachment.plan(text, listOf("2026-11-01-093005.m4a", "2026-11-01-094000.m4a"), emptyList())
        assertEquals(text, plan.text)
        assertEquals(listOf(AudioAttachment.Write("2026-11-01-093005.m4a", "2026-11-01-093005.m4a")), plan.writes)
    }

    @Test
    fun `si el nombre está tomado usa el siguiente y cambia el enlace`() {
        val plan = AudioAttachment.plan(
            "Idea ![[2026-11-01-093005.m4a]]",
            listOf("2026-11-01-093005.m4a"),
            listOf("2026-11-01-093005.M4A", "2026-11-01-093005-2.m4a"),
        )
        assertEquals("Idea ![[2026-11-01-093005-3.m4a]]", plan.text)
        assertEquals(listOf(AudioAttachment.Write("2026-11-01-093005.m4a", "2026-11-01-093005-3.m4a")), plan.writes)
    }

    @Test
    fun `sin audios pendientes no cambia nada`() {
        assertEquals(AudioAttachment.Plan("hola", emptyList()), AudioAttachment.plan("hola", emptyList(), listOf("x.m4a")))
    }
}
