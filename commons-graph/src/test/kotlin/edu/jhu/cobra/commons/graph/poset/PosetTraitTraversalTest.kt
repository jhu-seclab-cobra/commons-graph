package edu.jhu.cobra.commons.graph.poset

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetTrait: label-filtered adjacency and BFS traversal.
 *
 * - `getOutgoingEdges with label filters visible edges` -- outgoing filter
 * - `getIncomingEdges with label filters visible edges` -- incoming filter
 * - `getChildren with label returns visible children` -- node filter
 * - `getParents with label returns visible parents` -- node filter
 * - `getDescendants with label traverses only visible edges` -- BFS filter
 * - `getAncestors with label traverses only visible edges` -- BFS filter
 * - `getDescendants with label cycle back to start excludes start` -- start exclusion
 * - `getAncestors with label cycle back to start excludes start` -- start exclusion
 * - `getOutgoingEdges with label on node with no edges returns empty` -- boundary
 * - `getOutgoingEdges with cond filters by edge predicate` -- cond parameter
 * - `getIncomingEdges with cond filters by edge predicate` -- cond parameter
 * - `getAncestors diamond DAG deduplicates shared ancestors` -- DAG dedup
 */
internal class PosetTraitTraversalTest {
    private lateinit var graph: TestGraph

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // region PosetTrait (label-filtered traversal)

    @Test
    fun `getOutgoingEdges with label filters visible edges`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        graph.addEdge("a", "b", "r1", Label("v1"))
        graph.addEdge("a", "c", "r2", Label("v2"))

        val edges = graph.getOutgoingEdges("a", Label("v1")).toList()

        assertEquals(1, edges.size)
        assertEquals("b", edges.first().dstNid)
    }

    @Test
    fun `getIncomingEdges with label filters visible edges`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        graph.addEdge("a", "c", "r1", Label("v1"))
        graph.addEdge("b", "c", "r2", Label("other"))

        val edges = graph.getIncomingEdges("c", Label("v1")).toList()

        assertEquals(1, edges.size)
        assertEquals("a", edges.first().srcNid)
    }

    @Test
    fun `getChildren with label returns visible children`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        val label = Label("v1")
        graph.addEdge("a", "b", "r1", label)
        graph.addEdge("a", "c", "r2", Label("other"))

        val ids = graph.getChildren("a", label).map { it.id }.toList()

        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `getParents with label returns visible parents`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        val label = Label("v1")
        graph.addEdge("a", "c", "r1", label)
        graph.addEdge("b", "c", "r2", Label("other"))

        val ids = graph.getParents("c", label).map { it.id }.toList()

        assertEquals(listOf("a"), ids)
    }

    @Test
    fun `getDescendants with label traverses only visible edges`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        val label = Label("v1")
        graph.addEdge("a", "b", "r1", label)
        graph.addEdge("b", "c", "r2", Label("other"))

        val ids = graph.getDescendants("a", label).map { it.id }.toList()

        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `getAncestors with label traverses only visible edges`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        graph.addEdge("a", "b", "r1", Label("other"))
        graph.addEdge("b", "c", "r2", Label("v1"))

        val ids = graph.getAncestors("c", Label("v1")).map { it.id }.toList()

        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `getDescendants with label cycle back to start excludes start`() {
        graph.addNode("a")
        graph.addNode("b")
        val label = Label("v1")
        graph.addEdge("a", "b", "r1", label)
        graph.addEdge("b", "a", "r2", label)

        val ids = graph.getDescendants("a", label).map { it.id }.toList()

        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `getAncestors with label cycle back to start excludes start`() {
        graph.addNode("a")
        graph.addNode("b")
        val label = Label("v1")
        graph.addEdge("a", "b", "r1", label)
        graph.addEdge("b", "a", "r2", label)

        val ids = graph.getAncestors("a", label).map { it.id }.toList()

        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `getOutgoingEdges with label on node with no edges returns empty`() {
        graph.addNode("a")

        val edges = graph.getOutgoingEdges("a", Label("v1")).toList()

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `getOutgoingEdges with cond filters by edge predicate`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        val label = Label("v1")
        graph.addEdge("a", "b", "calls", label)
        graph.addEdge("a", "c", "data", label)

        val edges = graph.getOutgoingEdges("a", label) { it.eTag == "calls" }.toList()

        assertEquals(1, edges.size)
        assertEquals("b", edges.first().dstNid)
    }

    @Test
    fun `getIncomingEdges with cond filters by edge predicate`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        val label = Label("v1")
        graph.addEdge("a", "c", "calls", label)
        graph.addEdge("b", "c", "data", label)

        val edges = graph.getIncomingEdges("c", label) { it.eTag == "calls" }.toList()

        assertEquals(1, edges.size)
        assertEquals("a", edges.first().srcNid)
    }

    @Test
    fun `getAncestors diamond DAG deduplicates shared ancestors`() {
        val root = Label("root")
        val left = Label("left")
        val right = Label("right")
        val child = Label("child")
        graph.poset.setParents(child, mapOf("l" to left, "r" to right))
        graph.poset.setParents(left, mapOf("up" to root))
        graph.poset.setParents(right, mapOf("up" to root))

        val ancestors = graph.poset.getAncestors(child).toList()

        assertEquals(3, ancestors.size, "left, right, root — no duplicates")
        assertTrue(left in ancestors)
        assertTrue(right in ancestors)
        assertTrue(root in ancestors)
    }

    // endregion
}
