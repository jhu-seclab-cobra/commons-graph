package edu.jhu.cobra.commons.graph.poset

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetDftImpl: compare over the transitive closure, structural bounds, and cache.
 *
 * - `compare equal returns zero` -- reflexive
 * - `compare child vs parent returns negative` -- ordering
 * - `compare parent vs child returns positive` -- ordering
 * - `compare incomparable returns null` -- no relation
 * - `compare SUPREMUM greater than any` -- structural bound
 * - `compare INFIMUM less than any` -- structural bound
 * - `compare SUPREMUM vs INFIMUM` -- extreme pair
 * - `compare recognizes every parent as ancestor in a multi-parent DAG` -- DAG multi-parent ancestry
 * - `compare recognizes shared grandparent through both diamond paths` -- diamond reachability
 * - `compare throws on cyclic hierarchy naming a cycle label` -- cycle rejection
 * - `compare succeeds on deep chain without exhausting the call stack` -- closure build depth bound
 * - `compare reflects hierarchy change after setParents` -- closure invalidation
 * - `compare cache hit forward returns cached result` -- cache hit
 * - `compare cache hit reverse returns negated cached result` -- reverse cache hit
 */
internal class PosetDftImplCompareTest {
    private lateinit var graph: TestGraph

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // region IPoset (label hierarchy)

    @Test
    fun `compare equal returns zero`() {
        assertEquals(0, graph.poset.compare(Label("same"), Label("same")))
    }

    @Test
    fun `compare child vs parent returns negative`() {
        val parent = Label("parent")
        val child = Label("child")
        graph.poset.setParents(child, mapOf("up" to parent))

        val result = graph.poset.compare(child, parent)
        assertNotNull(result)
        assertTrue(result < 0)
    }

    @Test
    fun `compare parent vs child returns positive`() {
        val parent = Label("parent")
        val child = Label("child")
        graph.poset.setParents(child, mapOf("up" to parent))

        val result = graph.poset.compare(parent, child)
        assertNotNull(result)
        assertTrue(result > 0)
    }

    @Test
    fun `compare incomparable returns null`() {
        assertNull(graph.poset.compare(Label("a"), Label("b")))
    }

    @Test
    fun `compare SUPREMUM greater than any`() {
        val label = Label("any")
        assertEquals(1, graph.poset.compare(Label.SUPREMUM, label))
        assertEquals(-1, graph.poset.compare(label, Label.SUPREMUM))
    }

    @Test
    fun `compare INFIMUM less than any`() {
        val label = Label("any")
        assertEquals(-1, graph.poset.compare(Label.INFIMUM, label))
        assertEquals(1, graph.poset.compare(label, Label.INFIMUM))
    }

    @Test
    fun `compare SUPREMUM vs INFIMUM`() {
        assertEquals(1, graph.poset.compare(Label.SUPREMUM, Label.INFIMUM))
        assertEquals(-1, graph.poset.compare(Label.INFIMUM, Label.SUPREMUM))
    }

    @Test
    fun `compare recognizes every parent as ancestor in a multi-parent DAG`() {
        val child = Label("child")
        val left = Label("left")
        val right = Label("right")
        graph.poset.setParents(child, mapOf("l" to left, "r" to right))

        assertEquals(1, graph.poset.compare(left, child))
        assertEquals(1, graph.poset.compare(right, child))
        assertEquals(-1, graph.poset.compare(child, left))
        assertEquals(-1, graph.poset.compare(child, right))
    }

    @Test
    fun `compare recognizes shared grandparent through both diamond paths`() {
        val top = Label("top")
        val left = Label("left")
        val right = Label("right")
        val bottom = Label("bottom")
        graph.poset.setParents(left, mapOf("up" to top))
        graph.poset.setParents(right, mapOf("up" to top))
        graph.poset.setParents(bottom, mapOf("l" to left, "r" to right))

        assertEquals(1, graph.poset.compare(top, bottom))
        assertEquals(1, graph.poset.compare(left, bottom))
        assertEquals(1, graph.poset.compare(right, bottom))
        assertNull(graph.poset.compare(left, right))
    }

    @Test
    fun `compare throws on cyclic hierarchy naming a cycle label`() {
        val a = Label("cycA")
        val b = Label("cycB")
        graph.poset.setParents(a, mapOf("up" to b))
        graph.poset.setParents(b, mapOf("up" to a))

        val exception =
            assertFailsWith<IllegalStateException> {
                graph.poset.compare(a, b)
            }
        assertTrue("cyc" in exception.message.orEmpty())
    }

    @Test
    fun `compare succeeds on deep chain without exhausting the call stack`() {
        // Bottom-first creation gives every child a smaller storage ID than its
        // parent, so an ID-ordered closure build meets the whole uncached chain
        // at the first node instead of finding each parent already memoized.
        val depth = 2000
        for (i in 0 until depth - 1) {
            graph.poset.setParents(Label("deep$i"), mapOf("up" to Label("deep${i + 1}")))
        }

        // A 128 KiB stack cannot hold one closure-build frame per chain level;
        // the ancestor-closure build must bound its call depth independently of
        // the hierarchy depth.
        val stackBytes = 128L * 1024
        var result: Int? = null
        var failure: Throwable? = null
        val worker =
            Thread(null, {
                try {
                    result = graph.poset.compare(Label("deep${depth - 1}"), Label("deep0"))
                } catch (raised: Throwable) {
                    failure = raised
                }
            }, "deep-chain-compare", stackBytes)
        worker.start()
        worker.join()

        assertNull(failure)
        assertEquals(1, result)
    }

    @Test
    fun `compare reflects hierarchy change after setParents`() {
        val child = Label("movable")
        val oldParent = Label("oldParent")
        val newParent = Label("newParent")
        graph.poset.setParents(child, mapOf("up" to oldParent))
        assertEquals(1, graph.poset.compare(oldParent, child))

        graph.poset.setParents(child, mapOf("up" to newParent))

        assertEquals(1, graph.poset.compare(newParent, child))
        assertNull(graph.poset.compare(oldParent, child))
    }

    // endregion

    // region Cache and branch coverage

    @Test
    fun `compare cache hit forward returns cached result`() {
        val parent = Label("parent")
        val child = Label("child")
        graph.poset.setParents(child, mapOf("up" to parent))

        val first = graph.poset.compare(child, parent)
        val second = graph.poset.compare(child, parent)
        assertEquals(first, second)
        assertNotNull(second)
        assertTrue(second < 0)
    }

    @Test
    fun `compare cache hit reverse returns negated cached result`() {
        val parent = Label("parent")
        val child = Label("child")
        graph.poset.setParents(child, mapOf("up" to parent))

        val forward = graph.poset.compare(child, parent)
        val reverse = graph.poset.compare(parent, child)
        assertNotNull(forward)
        assertNotNull(reverse)
        assertEquals(-forward, reverse)
    }

    // endregion
}
