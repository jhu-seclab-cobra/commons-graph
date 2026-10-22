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
 * - `parents of an existing label changed leaves the closure correct` -- relabel with descendants
 * - `cycle through a new label is rejected` -- cycle still raised by the query
 * - `a self-parent is rejected by the query` -- self loop
 * - `incremental closure equals a fresh rebuild on random DAGs` -- seeded differential test
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
        repeat(200) { step ->
            val childIndex = random.nextInt(labels.size - 1)
            // Parents have a larger index than the child, so every state is acyclic.
            val parentCount = random.nextInt(4)
            val parents =
                (0 until parentCount).associate { slot ->
                    "p$slot" to labels[random.nextInt(childIndex + 1, labels.size)]
                }
            poset.setParents(labels[childIndex], parents)
            // Keep a closure alive between insertions so the incremental path runs.
            poset.compare(labels[random.nextInt(labels.size)], labels[random.nextInt(labels.size)])
            if (step % 20 == 19) assertSameOrder(labels)
        }
        assertSameOrder(labels)
    }

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
