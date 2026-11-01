package io.github.alexglezfelix.metalmind.core

import io.github.alexglezfelix.metalmind.core.Formatting.Block
import io.github.alexglezfelix.metalmind.core.Formatting.Inline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Los textos llevan la selección marcada con `«` y `»`, o el cursor con `|`. */
class FormattingTest {
    private fun parse(marked: String): Formatted {
        if ('|' in marked) {
            val at = marked.indexOf('|')
            return Formatted(marked.removeRange(at, at + 1), at, at)
        }
        val s = marked.indexOf('«')
        val e = marked.indexOf('»') - 1
        return Formatted(marked.replace("«", "").replace("»", ""), s, e)
    }

    private fun show(f: Formatted): String =
        if (f.start == f.end) f.text.substring(0, f.start) + "|" + f.text.substring(f.start)
        else f.text.substring(0, f.start) + "«" + f.text.substring(f.start, f.end) + "»" + f.text.substring(f.end)

    private fun inline(marked: String, style: Inline) = parse(marked).let { show(Formatting.inline(it.text, it.start, it.end, style)) }

    private fun block(marked: String, kind: Block) = parse(marked).let { show(Formatting.block(it.text, it.start, it.end, kind)) }

    @Test
    fun `negrita, cursiva y código envuelven la selección`() {
        assertEquals("una **«idea»** suelta", inline("una «idea» suelta", Inline.BOLD))
        assertEquals("una *«idea»* suelta", inline("una «idea» suelta", Inline.ITALIC))
        assertEquals("usa `«cargo test»`", inline("usa «cargo test»", Inline.CODE))
    }

    @Test
    fun `sin los espacios de los bordes`() {
        assertEquals("una **«idea»** suelta", inline("una« idea »suelta", Inline.BOLD))
        assertEquals("**«x»** ", inline("«x »", Inline.BOLD))
    }

    @Test
    fun `repetir quita el formato, con las marcas fuera o dentro de la selección`() {
        assertEquals("una «idea»", inline("una **«idea»**", Inline.BOLD))
        assertEquals("una «idea»", inline("una «**idea**»", Inline.BOLD))
        assertEquals("«idea»", inline("*«idea»*", Inline.ITALIC))
        assertEquals("«idea»", inline("`«idea»`", Inline.CODE))
    }

    @Test
    fun `negrita y cursiva se combinan`() {
        assertEquals("***«x»***", inline("**«x»**", Inline.ITALIC))
        assertEquals("***«x»***", inline("*«x»*", Inline.BOLD))
        assertEquals("*«x»*", inline("***«x»***", Inline.BOLD))
        assertEquals("**«x»**", inline("***«x»***", Inline.ITALIC))
    }

    @Test
    fun `sin selección, el par de marcas, salir de él y quitarlo vacío`() {
        assertEquals("ver **|**", inline("ver |", Inline.BOLD))
        assertEquals("ver **hola**|", inline("ver **hola|**", Inline.BOLD))
        assertEquals("ver |", inline("ver **|**", Inline.BOLD))
        assertEquals("*|*", inline("|", Inline.ITALIC))
        assertEquals("`x`| y", inline("`x|` y", Inline.CODE))
    }

    @Test
    fun `código de varias líneas va en un bloque`() {
        assertEquals("antes\n```\n«a\nb»\n```\ndespués", inline("antes\n«a\nb»\ndespués", Inline.CODE))
        assertEquals("x \n```\n«a\nb»\n```", inline("x «a\nb»", Inline.CODE))
    }

    @Test
    fun `lista y checkbox en el renglón del cursor`() {
        assertEquals("- comprar| pan", block("comprar| pan", Block.LIST))
        assertEquals("- [ ] |comprar", block("|comprar", Block.CHECKBOX))
        assertEquals("uno\n  - [ ] dos|\ntres", block("uno\n  dos|\ntres", Block.CHECKBOX))
        assertEquals("- |", block("|", Block.LIST))
    }

    @Test
    fun `repetir quita la marca`() {
        assertEquals("compr|ar", block("- compr|ar", Block.LIST))
        assertEquals("compr|ar", block("- [x] compr|ar", Block.CHECKBOX))
        assertEquals("|", block("- |", Block.LIST))
        assertEquals("|", block("- [ ] |", Block.CHECKBOX))
    }

    @Test
    fun `lista y checkbox se cambian uno por otro`() {
        assertEquals("- [ ] pa|n", block("- pa|n", Block.CHECKBOX))
        assertEquals("- pa|n", block("- [ ] pa|n", Block.LIST))
        assertEquals("pa|n", block("* pa|n", Block.LIST))
    }

    @Test
    fun `al final de un elemento empieza otro`() {
        assertEquals("- [ ] pan\n- [ ] |", block("- [ ] pan|", Block.CHECKBOX))
        assertEquals("  - pan\n  - |\nleche", block("  - pan|\nleche", Block.LIST))
    }

    @Test
    fun `varias líneas, todas o ninguna, sin contar las vacías`() {
        assertEquals("- «uno\n\n- dos»", block("«uno\n\ndos»", Block.LIST))
        assertEquals("«uno\n\ndos»", block("- «uno\n\n- dos»", Block.LIST))
        assertEquals("- [ ] «uno\n- [x] dos»", block("«uno\n- [x] dos»", Block.CHECKBOX))
        // La selección que acaba al inicio de un renglón no lo toca.
        assertEquals("- «uno\n»dos", block("«uno\n»dos", Block.LIST))
    }

    @Test
    fun `Enter sigue la lista`() {
        assertEquals("- pan\n- |", newline("- pan\n|"))
        assertEquals("  - [x] pan\n  - [ ] |", newline("  - [x] pan\n|"))
        assertEquals("- pan\n- |leche", newline("- pan\n|leche"))
    }

    @Test
    fun `Enter en un elemento vacío termina la lista`() {
        assertEquals("- pan\n|", newline("- pan\n- \n|"))
        assertEquals("- pan\n|", newline("- pan\n- [ ] \n|"))
    }

    @Test
    fun `Enter fuera de una lista no hace nada`() {
        assertNull(parse("pan\n|").let { Formatting.newline(it.text, it.start) })
        assertNull(parse("- pan|").let { Formatting.newline(it.text, it.start) })
    }

    private fun newline(marked: String) = parse(marked).let { show(Formatting.newline(it.text, it.start)!!) }

    @Test
    fun `solo un Enter sigue la lista`() {
        assertEquals(Formatted("- pan\n- ", 8, 8), Formatting.typedNewline("- pan", 5, 5, "- pan\n", 6))
        // Enter con texto seleccionado lo reemplaza.
        assertEquals(Formatted("- pan\n- ", 8, 8), Formatting.typedNewline("- pan leche", 5, 11, "- pan\n", 6))
        // Pegar varias líneas, o cualquier otro cambio, no.
        assertNull(Formatting.typedNewline("- pan", 5, 5, "- pan\nx", 7))
        assertNull(Formatting.typedNewline("- pan", 5, 5, "- pa\n", 5))
        assertNull(Formatting.typedNewline("- pan", 5, 5, "- pan", 5))
    }
}
