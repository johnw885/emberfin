package com.johnw.emberfin.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchKeyboardTest {
    @Test fun `keyboard is a to z then 1 to 0 in rows of six`() {
        val keys = SearchKeyboard.letters.map { it.c }.joinToString("")
        assertEquals("abcdefghijklmnopqrstuvwxyz1234567890", keys)
        assertEquals(6, SearchKeyboard.letters.chunked(SearchKeyboard.COLUMNS).size)
    }

    @Test fun `typing, space and delete`() {
        var q = ""
        "dune".forEach { q = SearchKeyboard.apply(q, SearchKey.Char(it)) }
        q = SearchKeyboard.apply(q, SearchKey.Space)
        q = SearchKeyboard.apply(q, SearchKey.Space)
        assertEquals("dune ", q)
        q = SearchKeyboard.apply(q, SearchKey.Delete)
        assertEquals("dune", q)
        assertEquals("", SearchKeyboard.apply(q, SearchKey.Clear))
        assertEquals("", SearchKeyboard.apply("", SearchKey.Space))
        assertEquals("", SearchKeyboard.apply("", SearchKey.Delete))
    }

    @Test fun `query length is capped`() {
        var q = ""
        repeat(60) { q = SearchKeyboard.apply(q, SearchKey.Char('a')) }
        assertEquals(SearchKeyboard.MAX_LENGTH, q.length)
    }

    @Test fun `blank queries show suggestions instead of searching`() {
        assertNull(SearchKeyboard.term(""))
        assertNull(SearchKeyboard.term("   "))
        assertEquals("the expanse", SearchKeyboard.term("  the   expanse "))
    }
}
