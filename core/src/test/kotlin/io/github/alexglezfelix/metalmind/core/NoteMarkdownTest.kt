package io.github.alexglezfelix.metalmind.core

import io.github.alexglezfelix.metalmind.core.NoteMarkdown.Style
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteMarkdownTest {
    private fun render(text: String) = NoteMarkdown.render(text)

    private fun text(md: String) = render(md).text

    /** Lo que lleva [style], como texto. */
    private fun styled(md: String, style: Style): List<String> {
        val r = render(md)
        return r.spans.filterIsInstance<NoteMarkdown.Styled>().filter { it.style == style }
            .map { r.text.substring(it.start, it.end) }
    }

    /** Los spans tocables como `texto -> destino`. */
    private fun links(md: String): List<String> {
        val r = render(md)
        return r.spans.mapNotNull {
            val shown = r.text.substring(it.start, it.end)
            when (it) {
                is NoteMarkdown.Tag -> "$shown -> tag:${it.name}"
                is NoteMarkdown.Link -> "$shown -> nota:${it.target}"
                is NoteMarkdown.Url -> "$shown -> ${it.url}"
                is NoteMarkdown.Styled -> null
            }
        }
    }

    @Test
    fun `sin frontmatter ni líneas en blanco al principio o al final`() {
        assertEquals("Hola", text("---\ntags: [a]\n---\n\n\nHola\n\n\n"))
        assertEquals("", text("---\ntags: [a]\n---\n"))
        assertEquals("a\nb", text("a\r\nb\r\n"))
    }

    @Test
    fun `encabezados sin los numerales`() {
        assertEquals("Título\nSub\nC#\n#tag", text("# Título\n### Sub ###\n## C#\n#tag"))
        assertEquals(listOf("Título"), styled("# Título\ntexto", Style.Heading1))
        assertEquals(listOf("Sub"), styled("### Sub ###", Style.Heading3))
        assertEquals(listOf("#tag -> tag:tag"), links("## C#\n#tag"))
    }

    @Test
    fun `negrita, cursiva, tachado y resaltado sin marcas`() {
        val md = "**negrita** y *cursiva* y _otra_ y ***las dos*** y ~~no~~ y ==ojo=="
        assertEquals("negrita y cursiva y otra y las dos y no y ojo", text(md))
        assertEquals(listOf("negrita", "las dos"), styled(md, Style.Bold))
        assertEquals(listOf("cursiva", "otra", "las dos"), styled(md, Style.Italic))
        assertEquals(listOf("no"), styled(md, Style.Strike))
        assertEquals(listOf("ojo"), styled(md, Style.Highlight))
    }

    @Test
    fun `énfasis anidado`() {
        val md = "**bold *it* más**"
        assertEquals("bold it más", text(md))
        assertEquals(listOf("it"), styled(md, Style.Italic))
        assertEquals(listOf("bold it más"), styled(md, Style.Bold))
    }

    @Test
    fun `lo que no cierra o va entre espacios se queda`() {
        assertEquals("2 * 3 * 4", text("2 * 3 * 4"))
        assertEquals("**abierto", text("**abierto"))
        assertEquals("a == b", text("a == b"))
        assertEquals("snake_case_name", text("snake_case_name"))
        assertEquals("~uno~", text("~uno~"))
    }

    @Test
    fun `escapes`() {
        assertEquals("*no* y #no", text("\\*no\\* y \\#no"))
        assertEquals(emptyList(), links("\\#no"))
    }

    @Test
    fun `código en línea sin marcar lo de dentro`() {
        val md = "usa `**x** #tag` y ``a ` b``"
        assertEquals("usa **x** #tag y a ` b", text(md))
        assertEquals(listOf("**x** #tag", "a ` b"), styled(md, Style.Code))
        assertEquals(emptyList(), links(md))
        assertEquals("un ` suelto", text("un ` suelto"))
    }

    @Test
    fun `un asterisco dentro de código no cierra la cursiva`() {
        assertEquals("a * b", text("*a `*` b*"))
        assertEquals(listOf("a * b"), styled("*a `*` b*", Style.Italic))
    }

    @Test
    fun `bloques de código sin las vallas`() {
        val md = "antes\n```kotlin\nval x = **1** // #no\n```\ndespués"
        assertEquals("antes\nval x = **1** // #no\ndespués", text(md))
        assertEquals(listOf("val x = **1** // #no"), styled(md, Style.CodeBlock))
        assertEquals(emptyList(), links(md))
    }

    @Test
    fun `una valla sin cerrar llega al final`() {
        assertEquals("a\n# no es título\n~~~", text("a\n~~~~\n# no es título\n~~~"))
        assertEquals(emptyList(), styled("~~~~\n# x\n~~~", Style.Heading1))
    }

    @Test
    fun `listas con viñeta, numeradas y de tareas`() {
        val md = "- uno\n  * dos\n    + tres\n1. primero\n10) décimo\n- [ ] pendiente\n- [x] hecha\n* [X] también"
        assertEquals("• uno\n◦ dos\n• tres\n1. primero\n10) décimo\n☐ pendiente\n☑ hecha\n☑ también", text(md))
        val r = render(md)
        assertEquals(listOf(1, 2, 3, 1, 1, 1, 1, 1), r.indents.map { it.level })
        assertEquals(listOf(2, 2, 2, 3, 4, 2, 2, 2), r.indents.map { it.hanging })
    }

    @Test
    fun `el texto de una lista también lleva formato`() {
        val md = "- **ojo** con #tag"
        assertEquals("• ojo con #tag", text(md))
        assertEquals(listOf("ojo"), styled(md, Style.Bold))
        assertEquals(listOf("#tag -> tag:tag"), links(md))
    }

    @Test
    fun `citas y callouts`() {
        val md = "> dijo *algo*\n>> anidada\n> [!warning] Cuidado\n> [!tip]"
        assertEquals("▎ dijo algo\n▎ ▎ anidada\n▎ Cuidado\n▎ Tip", text(md))
        assertEquals(listOf("▎ dijo algo", "▎ ▎ anidada", "▎ Cuidado", "▎ Tip"), styled(md, Style.Quote))
        assertEquals(listOf("Cuidado", "Tip"), styled(md, Style.Bold))
        assertEquals(listOf(2, 4, 2, 2), render(md).indents.map { it.hanging })
        assertEquals(listOf(0, 0, 0, 0), render(md).indents.map { it.level })
    }

    @Test
    fun `líneas horizontales`() {
        assertEquals(listOf("─".repeat(24), "─".repeat(24), "─".repeat(24)), styled("a\n\n---\n* * *\n___", Style.Rule))
        assertEquals("a --- b", text("a --- b"))
    }

    @Test
    fun `tags y wikilinks tocables, con alias`() {
        val md = "ver [[proyectos/rust|Rust]] y [[otra]] y ![[img.png]] #tag"
        assertEquals("ver Rust y otra y img.png #tag", text(md))
        assertEquals(
            listOf("Rust -> nota:proyectos/rust", "otra -> nota:otra", "img.png -> nota:img.png", "#tag -> tag:tag"),
            links(md),
        )
    }

    @Test
    fun `un wikilink no se rompe con el énfasis de alrededor`() {
        val md = "*mira [[a_b_c]]*"
        assertEquals("mira a_b_c", text(md))
        assertEquals(listOf("mira a_b_c"), styled(md, Style.Italic))
        assertEquals(listOf("a_b_c -> nota:a_b_c"), links(md))
    }

    @Test
    fun `links de markdown a la web y a otras notas`() {
        val md = "[la **doc**](https://ejemplo.org/a_(b)) y [nota](carpeta/Otra%20nota.md#sec) y [ancla](#sec)"
        assertEquals("la doc y nota y ancla", text(md))
        assertEquals(listOf("doc"), styled(md, Style.Bold))
        assertEquals(
            listOf("la doc -> https://ejemplo.org/a_(b)", "nota -> nota:carpeta/Otra nota.md"),
            links(md),
        )
    }

    @Test
    fun `una URL como texto del link no se duplica`() {
        assertEquals(listOf("https://a.org -> https://b.org"), links("[https://a.org](https://b.org)"))
    }

    @Test
    fun `imágenes como su texto alternativo`() {
        val md = "![un gato](gato.png) ![](fotos/perro.jpg)"
        assertEquals("un gato perro.jpg", text(md))
        assertEquals(listOf("un gato", "perro.jpg"), styled(md, Style.Italic))
        assertEquals(emptyList(), links(md))
    }

    @Test
    fun `URLs sueltas y entre ángulos`() {
        val md = "en https://ejemplo.org/x?y=1. Y (https://w.org/A_(b)) <https://c.org> <yo@correo.mx>"
        assertEquals("en https://ejemplo.org/x?y=1. Y (https://w.org/A_(b)) https://c.org yo@correo.mx", text(md))
        assertEquals(
            listOf(
                "https://ejemplo.org/x?y=1 -> https://ejemplo.org/x?y=1",
                "https://w.org/A_(b) -> https://w.org/A_(b)",
                "https://c.org -> https://c.org",
                "yo@correo.mx -> mailto:yo@correo.mx",
            ),
            links(md),
        )
        assertEquals(emptyList(), links("xhttps://no.org"))
    }

    @Test
    fun `comentarios ocultos`() {
        assertEquals("a  b\nc", text("a %%oculto%% b\n%%\nvarias\nlíneas #no\n%%\nc"))
        assertEquals(emptyList(), links("%%\n#no\n%%"))
    }

    @Test
    fun `comentarios que empiezan o terminan a media línea`() {
        assertEquals("Idea \nPárrafo importante\nOtro", text("Idea %%\nborrador\n%%\nPárrafo importante\nOtro"))
        assertEquals("Texto \nmás texto", text("Texto %%comentario\nque sigue%% más texto"))
        assertEquals("visible", text("%%\nsecreto\n%% visible"))
        assertEquals("a\nb", text("a\n%%\nx\n%%\nb"))
    }

    @Test
    fun `fórmulas tal cual`() {
        val md = "si \$a*b\$ y \$c*d\$ entonces \$\\{x\\}\$ y \$\$e_1 * e_2\$\$, cuesta \$5 o *\$6*"
        assertEquals("si \$a*b\$ y \$c*d\$ entonces \$\\{x\\}\$ y \$\$e_1 * e_2\$\$, cuesta \$5 o \$6", text(md))
        assertEquals(listOf("\$6"), styled(md, Style.Italic))
        assertEquals("\$\$\n*a*\n\$\$\nb", text("\$\$\n*a*\n\$\$\n*b*"))
    }

    @Test
    fun `bloques de código en listas y citas`() {
        val md = "1. Paso\n    ```bash\n    rm *.tmp # borra *todo*\n    ```\n2. Otro"
        assertEquals("1. Paso\nrm *.tmp # borra *todo*\n2. Otro", text(md))
        assertEquals(listOf("rm *.tmp # borra *todo*"), styled(md, Style.CodeBlock))
        assertEquals(listOf(1, 1, 1), render(md).indents.map { it.level })

        val quote = "> [!example]\n> ```python\n> x = a*b*c\n> ```\n> fin"
        assertEquals("▎ Example\n▎ x = a*b*c\n▎ fin", text(quote))
        assertEquals(listOf("x = a*b*c"), styled(quote, Style.CodeBlock))
        assertEquals("\tx", text("\t```\n\t\tx\n\t```"))
    }

    @Test
    fun `listas anidadas con tab o con cuatro espacios`() {
        assertEquals(listOf(1, 2, 3), render("- a\n\t- b\n\t\t- c").indents.map { it.level })
        assertEquals("• a\n◦ b\n• c", text("- a\n\t- b\n\t\t- c"))
        assertEquals(listOf(1, 2, 1), render("- a\n    - b\n- c").indents.map { it.level })
        // Un párrafo sin sangría termina la lista: la siguiente vuelve al primer nivel.
        assertEquals(listOf(1, 1), render("  - a\ntexto\n- b").indents.map { it.level })
    }

    @Test
    fun `tarea vacía`() {
        assertEquals("☐ \n☑ ", text("- [ ]\n- [x]"))
    }

    @Test
    fun `un destino con espacios no es link, salvo entre ángulos`() {
        assertEquals("[x](mi nota.md)", text("[x](mi nota.md)"))
        assertEquals(listOf("x -> nota:mi nota.md"), links("[x](<mi nota.md>)"))
        assertEquals(listOf("x -> https://a.org"), links("[x](https://a.org \"título\")"))
        assertEquals(listOf("x -> nota:otra.md"), links("[x](../../otra.md)"))
    }

    @Test
    fun `el título del callout lleva formato y links`() {
        val md = "> [!note] Título con [[nota]] y **negrita**"
        assertEquals("▎ Título con nota y negrita", text(md))
        assertEquals(listOf("nota -> nota:nota"), links(md))
    }

    @Test
    fun `tablas tal cual en monoespaciada`() {
        val md = "| a | **b** |\n|---|---|\n| #tag | [[x]] |"
        assertEquals(md, text(md))
        assertEquals(md.split('\n'), styled(md, Style.Table))
        assertEquals(listOf("#tag -> tag:tag", "[[x]] -> nota:x"), links(md))
    }

    @Test
    fun `los spans no se salen del texto`() {
        for (md in listOf("**a**\n\n", "- [ ] x\n", "```\nx\n", "> a\n\n", "[[a]]\n\n\n", "#t\n")) {
            val r = render(md)
            for (s in r.spans) assert(s.start < s.end && s.end <= r.text.length) { "$md: $s" }
            for (i in r.indents) assert(i.start <= i.end && i.end <= r.text.length) { "$md: $i" }
        }
    }

    @Test
    fun `miles de links o énfasis anidados no agotan la pila`() {
        // Con 5000 `[` seguidos, NoteMarkdown se llamaba a sí mismo una vez por
        // cada uno y la vista de la nota cerraba la app con StackOverflowError.
        val links = "[".repeat(5000) + "x" + "](y)".repeat(5000)
        assert(render(links).text.isNotEmpty())
        val emphasis = "*_".repeat(5000) + "x" + "_*".repeat(5000)
        assert(render(emphasis).text.contains('x'))
        // Por dentro del límite, igual que siempre.
        assertEquals("a", text("[".repeat(10) + "a" + "](b.md)".repeat(10)))
    }
}
