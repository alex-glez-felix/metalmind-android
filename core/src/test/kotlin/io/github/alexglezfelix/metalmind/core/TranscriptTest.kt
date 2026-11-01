package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptTest {
    @Test
    fun `junta los segmentos en un párrafo`() {
        assertEquals(
            "Comprar leche y pan. Llamar al dentista el lunes.",
            Transcript.clean(" Comprar leche y pan.\n  Llamar al dentista   el lunes.\n"),
        )
    }

    @Test
    fun `quita lo que no es habla`() {
        assertEquals(
            "Hola. Adiós.",
            Transcript.clean("[Música]\n Hola.\n(risas)\n[BLANK_AUDIO]\n♪ ♪\n Adiós.\n*aplausos*"),
        )
    }

    @Test
    fun `quita las frases que whisper inventa con silencio`() {
        assertEquals("", Transcript.clean(" Subtítulos realizados por la comunidad de Amara.org"))
        assertEquals("Idea: app de notas.", Transcript.clean(" Idea: app de notas.\n Subtitulado por la comunidad"))
    }

    @Test
    fun `deja el inglés mezclado y los paréntesis dentro de una frase`() {
        assertEquals(
            "Revisar el pull request (el de whisper) antes del deploy.",
            Transcript.clean(" Revisar el pull request (el de whisper) antes del deploy."),
        )
    }

    @Test
    fun `en un campo vacío queda solo el texto`() {
        assertEquals(Transcript.Inserted("Hola.", 5), Transcript.insert("", 0, 0, "Hola."))
    }

    @Test
    fun `al final de una palabra agrega un espacio`() {
        assertEquals(Transcript.Inserted("Idea: hola", 10), Transcript.insert("Idea:", 5, 5, "hola"))
    }

    @Test
    fun `en medio del texto separa de los dos lados`() {
        assertEquals(Transcript.Inserted("antes hola después", 10), Transcript.insert("antesdespués", 5, 5, "hola"))
    }

    @Test
    fun `reemplaza lo seleccionado`() {
        assertEquals(Transcript.Inserted("una hola nota", 8), Transcript.insert("una XXX nota", 4, 7, "hola"))
        assertEquals(Transcript.Inserted("una hola nota", 8), Transcript.insert("una XXX nota", 7, 4, "hola"))
    }

    @Test
    fun `tras un salto de línea no agrega espacio`() {
        assertEquals(Transcript.Inserted("título\nhola", 11), Transcript.insert("título\n", 7, 7, "hola"))
    }
}
