package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TagScannerTest {
    private fun tags(text: String) = TagScanner.tags(text)

    @Test
    fun `tags del cuerpo al inicio de línea o tras un espacio`() {
        assertEquals(listOf("rust", "gpui", "2024"), tags("#rust y #gpui\ndel #2024"))
        assertEquals(emptyList(), tags("texto#algo https://a.org/#frag [[nota#sección]]"))
    }

    @Test
    fun `la barra y la puntuación cortan el tag`() {
        assertEquals(listOf("a", "2024", "fin"), tags("#a/b #2024/10 #fin."))
    }

    @Test
    fun `los títulos no son tags`() {
        assertEquals(listOf("tag"), tags("# Título\n## Otro #tag\n#"))
    }

    @Test
    fun `solo separadores o escapado no es tag`() {
        assertEquals(emptyList(), tags("#- #_ \\#no"))
    }

    @Test
    fun `los tags de código no cuentan`() {
        val text = """
            #antes
            ```kotlin
            #dentro
            ```
            `#enlinea` y ``#doble ` #sigue`` #despues
            ~~~~
            #tilde
            ~~~
            #todavia
            ~~~~
            - ```
              #lista
              ```
            %% #comentario
            de varias líneas %% #fin
        """.trimIndent()
        assertEquals(listOf("antes", "despues", "fin"), tags(text))
    }

    @Test
    fun `los tags de links y fórmulas no cuentan`() {
        assertEquals(
            listOf("fuera", "fin"),
            tags("[ver #rust](https://x.org) [[nota|alias #x]] ![img #y](a.png \"t #z\") #fuera \$#no\$ \$x #m\$ \$\$\n#display\n\$\$ #fin"),
        )
    }

    @Test
    fun `lo que parece link o fórmula sin serlo no tapa los tags`() {
        assertEquals(listOf("a", "b", "c", "d"), tags("[sin cierre #a [x] #b \$ #c\$ \$5 y #d"))
        assertEquals(listOf("tag"), tags("[[\n#tag]]"))
    }

    @Test
    fun `un backtick sin cerrar no se come el resto`() {
        assertEquals(listOf("tag"), tags("un ` suelto #tag"))
    }

    @Test
    fun `un bloque sin cerrar llega al final`() {
        assertEquals(listOf("antes"), tags("#antes\n```\n#dentro"))
    }

    @Test
    fun `tags del frontmatter en lista en bloque`() {
        val text = "---\ntitle: x\ntags:\n  - rust\n  - \"#gpui\"\n  - 'año'\n  - con espacio\n  - #comentario\nother: 1\n---\n#cuerpo"
        assertEquals(listOf("rust", "gpui", "año", "cuerpo"), tags(text))
    }

    @Test
    fun `tags del frontmatter entre corchetes`() {
        assertEquals(listOf("rust", "2024", "7"), tags("---\ntags: [rust, \"2024\", 'a, b', 007] # nota\n---\n"))
        assertEquals(listOf("a", "b"), tags("---\ntags: [\n  a,\n  b,\n]\n---\n"))
    }

    @Test
    fun `valores como los lee YAML 1_2`() {
        assertEquals(
            listOf("rust", "31", "15", "1", "1000", "true", "false"),
            tags("---\ntags: [\"  rust \", 0x1F, 0o17, 1.0, 1e3, True, FALSE, 1.5, null]\n---\n"),
        )
    }

    @Test
    fun `un solo valor`() {
        assertEquals(listOf("rust"), tags("---\ntags: rust\n---\n"))
        assertEquals(listOf("2024"), tags("---\ntags: 2024\n---\n"))
        assertEquals(emptyList(), tags("---\ntags: dos tags\n---\n"))
        assertEquals(emptyList(), tags("---\ntags: #comentario\n---\n"))
        assertEquals(emptyList(), tags("---\ntags: ~\n---\n"))
        assertEquals(listOf("lista"), tags("---\ntags:\n- lista\n---\n"))
    }

    @Test
    fun `sin cerrar no es frontmatter`() {
        assertEquals(listOf("cuerpo"), tags("---\ntags: [rust]\n#cuerpo"))
    }

    @Test
    fun `con BOM y finales CRLF`() {
        assertEquals(listOf("rust", "body"), tags("﻿---\r\ntags: [rust]\r\n---\r\n#body\r\n"))
    }

    @Test
    fun `el cuerpo empieza en línea nueva tras el frontmatter`() {
        assertEquals(listOf("x"), tags("---\na: 1\n---\n#x"))
    }

    @Test
    fun `otras claves no son tags`() {
        assertEquals(emptyList(), tags("---\naliases: [rust]\nmytags: [x]\n  tags: [y]\n---\n"))
    }

    @Test
    fun `markup da dónde están los tags y los links del cuerpo`() {
        val text = "---\ntags: [fm]\n---\n#uno y [[otra nota|alias]] `#no` ![[img.png]] %% [[no]] %%"
        fun at(s: String) = text.indexOf(s).let { it until it + s.length }
        assertEquals(
            listOf(
                TagScanner.Tag(at("#uno").first, at("#uno").last + 1, "uno"),
                TagScanner.Link(at("[[otra nota|alias]]").first, at("[[otra nota|alias]]").last + 1, "otra nota"),
                TagScanner.Link(at("![[img.png]]").first, at("![[img.png]]").last + 1, "img.png"),
            ),
            TagScanner.markup(text),
        )
    }
}
