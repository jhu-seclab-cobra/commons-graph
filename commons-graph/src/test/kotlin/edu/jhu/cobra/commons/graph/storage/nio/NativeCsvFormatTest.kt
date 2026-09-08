package edu.jhu.cobra.commons.graph.storage.nio

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Tests for NativeCsvFormat: cell escaping and delimiter-aware line splitting.
 *
 * - `escape encodes delimiter backslash and line breaks` -- escape table
 * - `unescape inverts escape for every escaped sequence` -- round trip
 * - `splitCsvLine splits on unescaped delimiters only` -- escaped delimiter kept in cell
 * - `splitCsvLine treats delimiter after escaped backslash as separator` -- even backslash count
 * - `splitCsvLine keeps remainder verbatim once limit is reached` -- limit parameter
 * - `splitCsvLine on empty line yields one empty cell` -- boundary
 */
internal class NativeCsvFormatTest {
    @Test
    fun `escape encodes delimiter backslash and line breaks`() {
        assertEquals("a\\,b", NativeCsvFormat.escape("a,b"))
        assertEquals("a\\\\b", NativeCsvFormat.escape("a\\b"))
        assertEquals("a\\nb", NativeCsvFormat.escape("a\nb"))
        assertEquals("a\\r\\nb", NativeCsvFormat.escape("a\r\nb"))
        assertEquals("a\\rb", NativeCsvFormat.escape("a\rb"))
        assertEquals("a\\tb", NativeCsvFormat.escape("a\tb"))
    }

    @Test
    fun `unescape inverts escape for every escaped sequence`() {
        val samples = listOf("plain", "a,b", "a\\b", "a\nb", "a\r\nb", "a\rb", "a\tb", "trail\\", "mix,\\\n\t")

        for (sample in samples) {
            assertEquals(sample, NativeCsvFormat.unescape(NativeCsvFormat.escape(sample)), sample)
        }
    }

    @Test
    fun `splitCsvLine splits on unescaped delimiters only`() {
        assertEquals(listOf("a", "b", "c"), NativeCsvFormat.splitCsvLine("a,b,c"))
        assertEquals(listOf("a\\,b", "c"), NativeCsvFormat.splitCsvLine("a\\,b,c"))
        assertEquals(listOf("a", "", "c"), NativeCsvFormat.splitCsvLine("a,,c"))
    }

    @Test
    fun `splitCsvLine treats delimiter after escaped backslash as separator`() {
        assertEquals(listOf("a\\\\", "b"), NativeCsvFormat.splitCsvLine("a\\\\,b"))
        assertEquals(listOf("a\\\\\\,b"), NativeCsvFormat.splitCsvLine("a\\\\\\,b"))
    }

    @Test
    fun `splitCsvLine keeps remainder verbatim once limit is reached`() {
        assertEquals(listOf("name", "x,y,z"), NativeCsvFormat.splitCsvLine("name,x,y,z", limit = 2))
        assertEquals(listOf("only"), NativeCsvFormat.splitCsvLine("only", limit = 2))
    }

    @Test
    fun `splitCsvLine on empty line yields one empty cell`() {
        assertEquals(listOf(""), NativeCsvFormat.splitCsvLine(""))
    }
}
