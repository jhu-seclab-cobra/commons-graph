package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * Black-box tests for the ancestor closure of PosetDftImpl: once built, a label insertion
 * extends it by one row instead of discarding it (spec-poset.md Maintain closure).
 * "Reads" counts getOutgoingEdges calls on the poset storage; a closure lookup makes none.
 *
 * - `setParents with equal parents keeps the closure` -- no edge churn, no rebuild
 * - `a new label adds one row` -- insertion costs no rebuild
 * - `a label first seen as a parent gets its own parents later` -- row of a label with descendants
 * - `parents of an existing label changed leaves the closure correct` -- reparent with descendants
 * - `cycle through a new label is rejected` -- cycle still raised by the query
 * - `a self-parent is rejected by the query` -- self loop
 * - `incremental closure equals a fresh rebuild on random DAGs` -- seeded differential test after every step, with a freeze and a revert
 */
internal class PosetDftImplClosureTest {
    private lateinit var storage: CountingLayeredStorage
    private lateinit var poset: PosetDftImpl
    private val a = Label("a")
    private val b = Label("b")
    private val c = Label("c")
    private val d = Label("d")

    @BeforeTest
    fun setUp() {
        storage = CountingLayeredStorage()
        poset = PosetDftImpl(storage)
    }

    @Test
    fun `setParents with equal parents keeps the closure`() {
        poset.setParents(a, mapOf("up" to b))
        assertEquals(1, poset.compare(b, a))
        val edgesBefore = storage.edgeIDs

        poset.setParents(a, mapOf("up" to b))

        assertEquals(edgesBefore, storage.edgeIDs)
        assertEquals(0, storage.readsDuring { assertEquals(1, poset.compare(b, a)) })
    }

    @Test
    fun `a new label adds one row`() {
        poset.setParents(a, mapOf("up" to b))
        assertEquals(1, poset.compare(b, a))

        poset.setParents(c, mapOf("up" to a, "other" to d))

        val reads =
            storage.readsDuring {
                assertEquals(1, poset.compare(a, c))
                assertEquals(1, poset.compare(b, c))
                assertEquals(1, poset.compare(d, c))
                assertEquals(null, poset.compare(d, a))
            }
        assertEquals(0, reads)
    }

    @Test
    fun `a label first seen as a parent gets its own parents later`() {
        val top = Label("top")
        poset.setParents(a, mapOf("up" to b))
        assertEquals(1, poset.compare(b, a))

        poset.setParents(b, mapOf("up" to top))

        assertEquals(1, poset.compare(top, b))
        assertEquals(1, poset.compare(top, a))
    }

    @Test
    fun `parents of an existing label changed leaves the closure correct`() {
        poset.setParents(a, mapOf("up" to b))
        poset.setParents(b, mapOf("up" to c))
        assertEquals(1, poset.compare(c, a))

        poset.setParents(b, mapOf("up" to d))

        assertEquals(1, poset.compare(d, a))
        assertEquals(1, poset.compare(d, b))
        assertEquals(null, poset.compare(c, a))
        assertEquals(null, poset.compare(c, b))
    }

    @Test
    fun `parents of a childless label changed leaves the closure correct`() {
        poset.setParents(a, mapOf("up" to b))
        poset.setParents(b, mapOf("up" to c))
        assertEquals(1, poset.compare(c, a))

        poset.setParents(a, mapOf("up" to d))

        assertEquals(1, poset.compare(d, a))
        assertEquals(null, poset.compare(b, a))
        assertEquals(null, poset.compare(c, a))
    }

    @Test
    fun `cycle through a new label is rejected`() {
        poset.setParents(a, mapOf("up" to b))
        assertEquals(1, poset.compare(b, a))
        poset.setParents(c, mapOf("up" to a))
        assertEquals(1, poset.compare(b, c))

        poset.setParents(a, mapOf("up" to c))

        val failure = assertFailsWith<IllegalStateException> { poset.compare(a, c) }
        assertTrue("Label hierarchy cycle through" in failure.message.orEmpty())
    }

    @Test
    fun `a self-parent is rejected by the query`() {
        poset.setParents(a, mapOf("up" to b))
        assertEquals(1, poset.compare(b, a))

        poset.setParents(c, mapOf("up" to c))

        val failure = assertFailsWith<IllegalStateException> { poset.compare(a, b) }
        assertTrue("'c'" in failure.message.orEmpty())
    }

    @Test
    fun `incremental closure equals a fresh rebuild on random DAGs`() {
        val random = Random(20261022)
        val labels = List(40) { Label("L$it") }
        var usedAsParent = HashSet<Label>()
        var usedAtFreeze = emptySet<Label>()
        val inserted = HashSet<Label>()
        var insertedAtFreeze = emptySet<Label>()
        var incrementalSteps = 0
        var reparentedWhileFrozen = 0
        repeat(200) { step ->
            if (step == 80) {
                storage.layered.freeze()
                usedAtFreeze = usedAsParent.toSet()
                insertedAtFreeze = inserted.toSet()
            }
            if (step == 140) {
                storage.layered.clearActiveLayer()
                // The revert restores the hierarchy of step 80, so only its parent labels have children.
                usedAsParent = HashSet(usedAtFreeze)
                assertSameOrder(labels)
            }
            val childIndex = random.nextInt(labels.size - 1)
            val child = labels[childIndex]
            // Parents have a larger index than the child, so every state is acyclic.
            val parents =
                (0 until random.nextInt(4)).associate { slot ->
                    "p$slot" to labels[random.nextInt(childIndex + 1, labels.size)]
                }
            if (step in 80 until 140 && child in insertedAtFreeze && poset.getParents(child) != parents) {
                reparentedWhileFrozen++
            }
            poset.setParents(child, parents)
            // A label never used as a parent has no child label: its insertion costs no rebuild.
            // The previous step's comparison left a closure alive, except before step 0.
            val extended = step > 0 && child !in usedAsParent
            usedAsParent.addAll(parents.values)
            inserted.add(child)
            val reads = storage.readsDuring { assertSameOrder(labels) }
            if (extended) {
                assertEquals(0, reads, "step $step: the live closure was rebuilt")
                incrementalSteps++
            }
        }
        assertTrue(reparentedWhileFrozen > 0, "no frozen label was reparented before the revert")
        assertTrue(incrementalSteps > 20, "the incremental path ran only $incrementalSteps times")
    }

    // Compares every pair on the live instance with a fresh instance over a copy of the storage.
    private fun assertSameOrder(labels: List<Label>) {
        val copy = NativeStorageImpl()
        storage.transferTo(copy)
        val fresh = PosetDftImpl(copy)
        for (x in labels) {
            for (y in labels) {
                assertEquals(fresh.compare(x, y), poset.compare(x, y), "compare($x, $y)")
            }
        }
    }
}
