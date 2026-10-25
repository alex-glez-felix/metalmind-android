package io.github.alexglezfelix.metalmind.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureTest {
    private val now = LocalDateTime.of(2026, 10, 18, 9, 5, 7)

    /** Una carpeta en memoria; [racing] son nombres que "otro" crea justo antes. */
    private class FakeFolder(
        existing: List<String> = emptyList(),
        private val racing: Set<String> = emptySet(),
    ) : NoteFolder {
        val files = linkedMapOf<String, String>().apply { existing.forEach { put(it, "") } }

        override fun names(): Collection<String> = files.keys.toList()

        override fun create(name: String, content: String): Boolean {
            if (name in racing) files[name] = "de otro"
            if (files.keys.any { it.equals(name, ignoreCase = true) }) return false
            files[name] = content
            return true
        }
    }

    @Test
    fun `guarda el texto con el nombre de la primera línea`() {
        val folder = FakeFolder()
        val result = Capture.save(folder, "Comprar leche\ny pan\n\n", now)
        assertEquals(CaptureResult.Saved("comprar-leche.md"), result)
        assertEquals("Comprar leche\ny pan\n", folder.files["comprar-leche.md"])
    }

    @Test
    fun `nunca pisa una nota`() {
        val folder = FakeFolder(listOf("idea.md", "idea-2.md"))
        assertEquals(CaptureResult.Saved("idea-3.md"), Capture.save(folder, "Idea", now))
        assertEquals("", folder.files["idea.md"])
    }

    @Test
    fun `los nombres tomados no distinguen mayúsculas`() {
        val folder = FakeFolder(listOf("Idea.md"))
        assertEquals(CaptureResult.Saved("idea-2.md"), Capture.save(folder, "idea", now))
    }

    @Test
    fun `si otro crea el nombre a la vez pasa al siguiente sufijo`() {
        val folder = FakeFolder(racing = setOf("idea.md"))
        assertEquals(CaptureResult.Saved("idea-2.md"), Capture.save(folder, "idea", now))
        assertEquals("de otro", folder.files["idea.md"])
    }

    @Test
    fun `sin letras usa la fecha y la hora`() {
        val folder = FakeFolder()
        assertEquals(CaptureResult.Saved("2026-10-18-090507.md"), Capture.save(folder, "🔥", now))
    }

    @Test
    fun `el texto vacío no crea nada`() {
        val folder = FakeFolder()
        assertEquals(CaptureResult.Empty, Capture.save(folder, " \n\t ", now))
        assertEquals(emptyMap(), folder.files)
    }

    @Test
    fun `se rinde después del último sufijo`() {
        val all = (1..Capture.MAX_ATTEMPT).map { NoteName.fileName("idea", it) }
        val folder = FakeFolder(all)
        assertEquals(CaptureResult.NameTaken("idea"), Capture.save(folder, "idea", now))
    }
}
