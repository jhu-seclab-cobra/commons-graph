package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/*
 * Black-box tests for PosetDftImpl over a LayeredStorageImpl whose active layer is
 * reverted (design-label.md revert coherence; spec-poset.md Invalidate).
 *
 * - `getParents of label created after freeze is empty after revert` -- evicted label node
 * - `allLabels excludes label created after freeze after revert` -- registry follows storage
 * - `getAncestors of label created after freeze is empty after revert` -- closure discarded
 * - `setParents on frozen label replaces its frozen parent edges` -- tombstoned parent edges
 * - `getParents of frozen label is restored after revert` -- tombstones revert
 * - `getAncestors of frozen label is restored after revert` -- closure rebuilt from storage
 * - `setParents on label created after freeze succeeds after revert` -- label node re-created
 * - `revert removes the rows of removed labels only` -- frozen rows are kept, not recomputed
 * - `compare of a reparented frozen label is restored after revert` -- tombstoned edges restored
 * - `compare of a frozen label stripped of parents is restored after revert` -- empty replacement
 * - `compare of a frozen descendant is restored after its ancestor is reparented and reverted` -- descendant rows
 * - `compare of a parentless frozen label that gained only new parents is restored after revert` -- no restored edge
 * - `revert keeps the rows when only a parent edge property was promoted` -- promoted edge, same structure
 * - `revert keeps the rows when a surviving label gains and loses a child` -- removed edge of a removed source
 */
internal class PosetDftImplRevertTest {
    private val frozen = Label("frozen")
    private val frozenParent = Label("frozenParent")
    private val child = Label("child")
    private val other = Label("other")
    private lateinit var counted: CountingLayeredStorage
    private lateinit var storage: LayeredStorageImpl
    private lateinit var poset: PosetDftImpl

    @BeforeTest
    fun setUp() {
        counted = CountingLayeredStorage()
        storage = counted.layered
        poset = PosetDftImpl(counted)
        poset.setParents(frozen, mapOf("orig" to frozenParent))
        storage.freeze()
    }

    @Test
    fun `getParents of label created after freeze is empty after revert`() {
        poset.setParents(child, mapOf("p" to frozen))

        storage.clearActiveLayer()

        assertEquals(emptyMap(), poset.getParents(child))
    }

    @Test
    fun `allLabels excludes label created after freeze after revert`() {
        poset.setParents(child, mapOf("p" to frozen))

        storage.clearActiveLayer()

        assertFalse(child in poset.allLabels)
    }

    @Test
    fun `getAncestors of label created after freeze is empty after revert`() {
        poset.setParents(child, mapOf("p" to frozen))
        poset.getAncestors(child).toList()

        storage.clearActiveLayer()

        assertEquals(emptyList(), poset.getAncestors(child).toList())
    }

    @Test
    fun `setParents on frozen label replaces its frozen parent edges`() {
        poset.setParents(frozen, mapOf("x" to other))

        assertEquals(mapOf("x" to other), poset.getParents(frozen))
    }

    @Test
    fun `getParents of frozen label is restored after revert`() {
        poset.setParents(frozen, mapOf("x" to other))

        storage.clearActiveLayer()

        assertEquals(mapOf("orig" to frozenParent), poset.getParents(frozen))
    }

    @Test
    fun `getAncestors of frozen label is restored after revert`() {
        poset.setParents(frozen, mapOf("x" to other))
        poset.getAncestors(frozen).toList()

        storage.clearActiveLayer()

        assertEquals(listOf(frozenParent), poset.getAncestors(frozen).toList())
    }

    @Test
    fun `setParents on label created after freeze succeeds after revert`() {
        poset.setParents(child, mapOf("p" to frozen))
        storage.clearActiveLayer()

        poset.setParents(child, mapOf("p" to frozenParent))

        assertEquals(mapOf("p" to frozenParent), poset.getParents(child))
    }

    @Test
    fun `revert removes the rows of removed labels only`() {
        assertEquals(1, poset.compare(frozenParent, frozen))
        poset.setParents(child, mapOf("p" to frozen))
        assertEquals(1, poset.compare(frozenParent, child))

        storage.clearActiveLayer()

        val reads =
            counted.readsDuring {
                assertEquals(1, poset.compare(frozenParent, frozen))
                assertNull(poset.compare(frozen, child))
            }
        assertEquals(0, reads)
    }

    @Test
    fun `compare of a reparented frozen label is restored after revert`() {
        poset.setParents(frozen, mapOf("x" to other))
        assertEquals(1, poset.compare(other, frozen))
        assertNull(poset.compare(frozenParent, frozen))

        storage.clearActiveLayer()

        assertEquals(1, poset.compare(frozenParent, frozen))
        assertNull(poset.compare(other, frozen))
    }

    @Test
    fun `compare of a frozen label stripped of parents is restored after revert`() {
        poset.setParents(frozen, emptyMap())
        assertNull(poset.compare(frozenParent, frozen))

        storage.clearActiveLayer()

        assertEquals(1, poset.compare(frozenParent, frozen))
    }

    @Test
    fun `compare of a frozen descendant is restored after its ancestor is reparented and reverted`() {
        val grandchild = Label("grandchild")
        poset.setParents(grandchild, mapOf("p" to frozen))
        storage.freeze()
        assertEquals(1, poset.compare(frozenParent, grandchild))
        poset.setParents(frozen, emptyMap())
        assertNull(poset.compare(frozenParent, grandchild))

        storage.clearActiveLayer()

        assertEquals(1, poset.compare(frozenParent, grandchild))
    }

    @Test
    fun `compare of a parentless frozen label that gained only new parents is restored after revert`() {
        poset.setParents(frozenParent, mapOf("up" to other))
        assertEquals(1, poset.compare(other, frozen))

        storage.clearActiveLayer()

        assertNull(poset.compare(other, frozen))
        assertEquals(1, poset.compare(frozenParent, frozen))
    }

    @Test
    fun `revert keeps the rows when only a parent edge property was promoted`() {
        assertEquals(1, poset.compare(frozenParent, frozen))
        storage.setEdgeProperties(storage.edgeIDs.single(), mapOf("k" to "active".strVal))

        storage.clearActiveLayer()

        val reads = counted.readsDuring { assertEquals(1, poset.compare(frozenParent, frozen)) }
        assertEquals(0, reads)
    }

    @Test
    fun `revert keeps the rows when a surviving label gains and loses a child`() {
        assertEquals(1, poset.compare(frozenParent, frozen))
        poset.setParents(child, mapOf("p" to frozenParent))
        poset.setParents(Label("second"), mapOf("p" to child))
        assertEquals(1, poset.compare(frozenParent, child))

        storage.clearActiveLayer()

        val reads = counted.readsDuring { assertEquals(1, poset.compare(frozenParent, frozen)) }
        assertEquals(0, reads)
    }
}
