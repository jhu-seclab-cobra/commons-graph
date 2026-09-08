package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.EntityNotExistException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetTrait: label-aware edge operations.
 *
 * - `addEdge with label assigns label` -- basic assignment
 * - `addEdge with label existing edge adds label` -- accumulation
 * - `addEdge with label same label twice is idempotent` -- duplicate label
 * - `addEdge with label missing src throws EntityNotExistException` -- guard
 * - `addEdge with label missing dst throws EntityNotExistException` -- guard
 * - `delEdge with label removes only that label` -- selective removal
 * - `delEdge last label removes edge entirely` -- cleanup
 * - `delEdge with label nonexistent edge is no-op` -- no-op
 * - `delEdge with label not on edge retains edge` -- non-matching
 * - `delEdge with label on unlabeled edge retains edge` -- unlabeled boundary
 * - `addEdge with label on non-existent edge creates then labels` -- null path
 */
internal class PosetTraitTest {
    private lateinit var graph: TestGraph

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // region PosetTrait (label-aware edge operations)

    @Test
    fun `addEdge with label assigns label`() {
        graph.addNode("a")
        graph.addNode("b")
        val label = Label("v1")

        val edge = graph.addEdge("a", "b", "rel", label)

        assertTrue(label in edge.labels)
    }

    @Test
    fun `addEdge with label existing edge adds label`() {
        graph.addNode("a")
        graph.addNode("b")
        val l1 = Label("a")
        val l2 = Label("b")
        graph.addEdge("a", "b", "rel", l1)

        graph.addEdge("a", "b", "rel", l2)

        val edge = graph.getEdge("a", "b", "rel")!!
        assertTrue(edge.labels.containsAll(setOf(l1, l2)))
    }

    @Test
    fun `addEdge with label same label twice is idempotent`() {
        graph.addNode("a")
        graph.addNode("b")
        val label = Label("v1")
        graph.addEdge("a", "b", "rel", label)

        graph.addEdge("a", "b", "rel", label)

        val edge = graph.getEdge("a", "b", "rel")!!
        assertEquals(1, edge.labels.size)
    }

    @Test
    fun `addEdge with label missing src throws EntityNotExistException`() {
        graph.addNode("b")
        assertFailsWith<EntityNotExistException> { graph.addEdge("a", "b", "rel", Label("v1")) }
    }

    @Test
    fun `addEdge with label missing dst throws EntityNotExistException`() {
        graph.addNode("a")
        assertFailsWith<EntityNotExistException> { graph.addEdge("a", "b", "rel", Label("v1")) }
    }

    @Test
    fun `delEdge with label removes only that label`() {
        graph.addNode("a")
        graph.addNode("b")
        val l1 = Label("a")
        val l2 = Label("b")
        graph.addEdge("a", "b", "rel", l1)
        graph.addEdge("a", "b", "rel", l2)

        graph.delEdge("a", "b", "rel", l1)

        assertTrue(graph.containEdge("a", "b", "rel"))
        val edge = graph.getEdge("a", "b", "rel")!!
        assertFalse(l1 in edge.labels)
        assertTrue(l2 in edge.labels)
    }

    @Test
    fun `delEdge last label removes edge entirely`() {
        graph.addNode("a")
        graph.addNode("b")
        val label = Label("only")
        graph.addEdge("a", "b", "rel", label)

        graph.delEdge("a", "b", "rel", label)

        assertFalse(graph.containEdge("a", "b", "rel"))
    }

    @Test
    fun `delEdge with label nonexistent edge is no-op`() {
        graph.addNode("a")
        graph.addNode("b")

        graph.delEdge("a", "b", "missing", Label("v1"))

        assertFalse(graph.containEdge("a", "b", "missing"))
    }

    @Test
    fun `delEdge with label not on edge retains edge`() {
        graph.addNode("a")
        graph.addNode("b")
        val label = Label("a")
        graph.addEdge("a", "b", "rel", label)

        graph.delEdge("a", "b", "rel", Label("unrelated"))

        assertTrue(graph.containEdge("a", "b", "rel"))
        assertTrue(label in graph.getEdge("a", "b", "rel")!!.labels)
    }

    @Test
    fun `delEdge with label on unlabeled edge retains edge`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addEdge("a", "b", "rel")

        graph.delEdge("a", "b", "rel", Label("unrelated"))

        assertTrue(graph.containEdge("a", "b", "rel"))
    }

    // endregion

    // region Cache and branch coverage

    @Test
    fun `addEdge with label on non-existent edge creates then labels`() {
        graph.addNode("a")
        graph.addNode("b")
        assertNull(graph.getEdge("a", "b", "fresh"))

        val label = Label("v1")
        val edge = graph.addEdge("a", "b", "fresh", label)

        assertNotNull(edge)
        assertTrue(graph.containEdge("a", "b", "fresh"))
        assertTrue(label in edge.labels)
    }

    // endregion
}
