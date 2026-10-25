package io.github.alexglezfelix.metalmind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrontmatterTest {
    @Test
    fun `el yaml y el cuerpo`() {
        assertEquals(Frontmatter.Split("a: 1\n", 13), Frontmatter.split("---\na: 1\n---\ncuerpo"))
        assertEquals(Frontmatter.Split("", 8), Frontmatter.split("---\n---\n"))
        assertEquals(Frontmatter.Split("a: 1\n", 12), Frontmatter.split("---\na: 1\n---"))
    }

    @Test
    fun `los delimitadores admiten espacios y CRLF al final`() {
        assertEquals(Frontmatter.Split("a: 1\r\n", 18), Frontmatter.split("﻿--- \r\na: 1\r\n---\r\nx"))
    }

    @Test
    fun `sin cerrar o sin empezar en la primera línea no hay`() {
        assertNull(Frontmatter.split("---\na: 1\n"))
        assertNull(Frontmatter.split("---"))
        assertNull(Frontmatter.split("\n---\na: 1\n---\n"))
        assertNull(Frontmatter.split("----\na\n---\n"))
        assertEquals(1, Frontmatter.bodyStart("﻿hola"))
        assertEquals(0, Frontmatter.bodyStart("hola"))
    }
}
