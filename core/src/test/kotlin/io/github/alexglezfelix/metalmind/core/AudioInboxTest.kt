package io.github.alexglezfelix.metalmind.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioInboxTest {
    private val now = LocalDateTime.of(2026, 11, 1, 9, 30, 5)

    @Test
    fun `el nombre es la fecha y la hora y el enlace es el de Metalmind`() {
        assertEquals("2026-11-01-093005.m4a", AudioInbox.fileName(now))
        assertEquals("![[2026-11-01-093005.m4a]]", AudioInbox.embed("2026-11-01-093005.m4a"))
        assertEquals("buzon/audio", AudioInbox.PATH)
    }

    @Test
    fun `nunca pisa un audio que ya está, sin distinguir mayúsculas`() {
        assertEquals(
            "2026-11-01-093005-3.m4a",
            AudioInbox.fileName(now, listOf("2026-11-01-093005.M4A", "2026-11-01-093005-2.m4a", "otra.m4a")),
        )
        assertEquals("2026-11-01-093005-2.m4a", AudioInbox.nextFree("2026-11-01-093005.m4a", listOf("2026-11-01-093005.m4a")))
    }

    @Test
    fun `solo cuentan los m4a que no están ocultos`() {
        assertTrue(AudioInbox.isAudio("2026-11-01-093005.m4a"))
        assertTrue(AudioInbox.isAudio("2026-11-01-093005.M4A"))
        // Syncthing escribe así mientras copia; el archivo de verdad llega al terminar.
        assertFalse(AudioInbox.isAudio(".syncthing.2026-11-01-093005.m4a.tmp"))
        assertFalse(AudioInbox.isAudio(".2026-11-01-093005.m4a"))
        assertFalse(AudioInbox.isAudio("2026-11-01-093005.md"))
    }

    // El contrato con el script que transcribe fuera del teléfono (README, "Audios pendientes").

    @Test
    fun `la nota que escribe la app marca el audio como transcrito`() {
        val note = "Comprar pan.\n${AudioInbox.embed("2026-11-01-093005.m4a")}\n"
        assertTrue(AudioInbox.isTranscribed("2026-11-01-093005.m4a", sequenceOf("otra nota", note)))
    }

    @Test
    fun `cuenta cualquier forma de nombrarlo`() {
        val name = "2026-11-01-093005.m4a"
        for (
            note in listOf(
                "![[2026-11-01-093005.m4a]]",
                "[[2026-11-01-093005.m4a|el audio]]",
                "![[buzon/audio/2026-11-01-093005.m4a]]",
                "[audio](buzon/audio/2026-11-01-093005.m4a)",
                "audio: 2026-11-01-093005.m4a",
                "![[2026-11-01-093005.M4A]]",
            )
        ) {
            assertTrue(AudioInbox.names(note, name), note)
        }
    }

    @Test
    fun `un nombre no cuenta por otro que lo contiene`() {
        assertFalse(AudioInbox.names("![[2026-11-01-093005-2.m4a]]", "2026-11-01-093005.m4a"))
        assertFalse(AudioInbox.names("![[2026-11-01-093005.m4a]]", "2026-11-01-093005-2.m4a"))
        assertFalse(AudioInbox.names("![[x2026-11-01-093005.m4a]]", "2026-11-01-093005.m4a"))
        assertFalse(AudioInbox.names("![[2026-11-01-093005.m4a_viejo]]", "2026-11-01-093005.m4a"))
        // El punto del nombre es un punto, no cualquier letra.
        assertFalse(AudioInbox.names("![[2026-11-01-093005xm4a]]", "2026-11-01-093005.m4a"))
    }

    @Test
    fun `pendientes son los audios que ninguna nota nombra`() {
        val files = listOf(
            "2026-11-01-093005.m4a",
            "2026-11-01-100000.m4a",
            "2026-11-01-100000-2.m4a",
            ".syncthing.2026-11-01-110000.m4a.tmp",
            "notas.txt",
        )
        val notes = sequenceOf("Idea\n![[2026-11-01-093005.m4a]]", "Otra\n![[2026-11-01-100000-2.m4a]]")
        assertEquals(listOf("2026-11-01-100000.m4a"), AudioInbox.pending(files, notes))
        assertEquals(files.take(3), AudioInbox.pending(files, emptySequence()))
    }
}
