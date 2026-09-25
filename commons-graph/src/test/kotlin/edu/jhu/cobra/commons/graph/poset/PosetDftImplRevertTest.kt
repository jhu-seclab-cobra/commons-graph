package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
 */
internal class PosetDftImplRevertTest {
    private val frozen = Label("frozen")
    private val frozenParent = Label("frozenParent")
    private val child = Label("child")
    private val other = Label("other")
    private lateinit var storage: LayeredStorageImpl
    private lateinit var poset: PosetDftImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
        poset = PosetDftImpl(storage)
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
}
