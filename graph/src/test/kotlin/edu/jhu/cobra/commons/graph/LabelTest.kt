package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

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
    fun `test Label data class equality`() {
        val a = Label("same")
        val b = Label("same")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `test Label data class inequality`() {
        val a = Label("one")
        val b = Label("two")
        assertNotEquals(a, b)
    }

    @Test
    fun `test INFIMUM sentinel`() {
        assertEquals("infimum", Label.INFIMUM.core)
    }

    @Test
    fun `test SUPREMUM sentinel`() {
        assertEquals("supremum", Label.SUPREMUM.core)
    }

    @Test
    fun `test INFIMUM and SUPREMUM are distinct`() {
        assertNotEquals(Label.INFIMUM, Label.SUPREMUM)
    }
}
