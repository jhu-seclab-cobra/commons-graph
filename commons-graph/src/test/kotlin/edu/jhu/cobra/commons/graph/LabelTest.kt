package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Black-box tests for Label value class, INFIMUM/SUPREMUM sentinels.
 *
 * - `Label created from string exposes core` — verifies primary constructor
 * - `Label created from StrVal exposes core` — verifies secondary constructor
 * - `Labels with same core are equal` — verifies value class equality
 * - `Labels with same core have equal hashCode` — verifies hashCode consistency
 * - `Labels with different core are not equal` — verifies inequality
 * - `INFIMUM core is Int MIN_VALUE string` — verifies INFIMUM sentinel value
 * - `SUPREMUM core is Int MAX_VALUE string` — verifies SUPREMUM sentinel value
 * - `INFIMUM and SUPREMUM are distinct` — verifies sentinels differ
 */
internal class LabelTest {

    @Test
    fun `Label created from string exposes core`() {
        val label = Label("test")

        assertEquals("test", label.core)
    }

    @Test
    fun `Label created from StrVal exposes core`() {
        val label = Label("test".strVal)

        assertEquals("test", label.core)
    }

    @Test
    fun `Labels with same core are equal`() {
        val a = Label("same")
        val b = Label("same")

        assertEquals(a, b)
    }

    @Test
    fun `Labels with same core have equal hashCode`() {
        val a = Label("same")
        val b = Label("same")

        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Labels with different core are not equal`() {
        val a = Label("one")
        val b = Label("two")

        assertNotEquals(a, b)
    }

    @Test
    fun `INFIMUM core is Int MIN_VALUE string`() {
        assertEquals(Int.MIN_VALUE.toString(), Label.INFIMUM.core)
    }

    @Test
    fun `SUPREMUM core is Int MAX_VALUE string`() {
        assertEquals(Int.MAX_VALUE.toString(), Label.SUPREMUM.core)
    }

    @Test
    fun `INFIMUM and SUPREMUM are distinct`() {
        assertNotEquals(Label.INFIMUM, Label.SUPREMUM)
    }
}
