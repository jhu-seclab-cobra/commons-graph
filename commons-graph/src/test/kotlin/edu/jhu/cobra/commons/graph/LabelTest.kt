package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LabelTest {
    @Test
    fun `test Label creation from string`() {
        val label = Label("test")
        assertEquals("test", label.core)
    }

    @Test
    fun `test Label creation from StrVal`() {
        val label = Label("test".strVal)
        assertEquals("test", label.core)
    }

    @Test
    fun `test Label value class equality`() {
        val a = Label("same")
        val b = Label("same")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `test Label value class inequality`() {
        val a = Label("one")
        val b = Label("two")
        assertNotEquals(a, b)
    }

    @Test
    fun `test INFIMUM sentinel`() {
        assertEquals(Int.MIN_VALUE.toString(), Label.INFIMUM.core)
    }

    @Test
    fun `test SUPREMUM sentinel`() {
        assertEquals(Int.MAX_VALUE.toString(), Label.SUPREMUM.core)
    }

    @Test
    fun `test INFIMUM and SUPREMUM are distinct`() {
        assertNotEquals(Label.INFIMUM, Label.SUPREMUM)
    }
}
