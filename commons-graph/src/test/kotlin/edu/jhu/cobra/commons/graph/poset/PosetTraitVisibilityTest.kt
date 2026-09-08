package edu.jhu.cobra.commons.graph.poset

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetTrait: label visibility rules and filterVisitable.
 *
 * - `parent label sees child label edges` -- visibility rule
 * - `SUPREMUM label sees all edges` -- SUPREMUM visibility
 * - `query with label on edges without labels returns empty` -- boundary
 * - `multi-level transitive visibility grandparent sees grandchild edges` -- deep hierarchy
 * - `INFIMUM label sees only INFIMUM-labeled edges` -- INFIMUM visibility
 * - `setParents with empty map removes all parents` -- boundary
 * - `allLabels includes user-registered labels` -- registered labels
 * - `filterVisitable comparable labels on separate edges keeps both` -- pure covering, no shadowing
 * - `filterVisitable INFIMUM edge stays visible beside context edge` -- INFIMUM coexistence
 * - `filterVisitable multiple covered labels on one edge keeps edge` -- label accumulation
 */
internal class PosetTraitVisibilityTest {
    private lateinit var graph: TestGraph

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // region PosetTrait (label-filtered traversal)

    @Test
    fun `parent label sees child label edges`() {
        graph.addNode("a")
        graph.addNode("b")
        val parent = Label("parent")
        val child = Label("child")
        graph.poset.setParents(child, mapOf("up" to parent))
        graph.addEdge("a", "b", "rel", child)

        val edges = graph.getOutgoingEdges("a", parent).toList()

        assertEquals(1, edges.size)
    }

    @Test
    fun `SUPREMUM label sees all edges`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addEdge("a", "b", "rel", Label("any"))

        val edges = graph.getOutgoingEdges("a", Label.SUPREMUM).toList()

        assertEquals(1, edges.size)
    }

    @Test
    fun `query with label on edges without labels returns empty`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addEdge("a", "b", "rel")

        val edges = graph.getOutgoingEdges("a", Label("v1")).toList()

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `multi-level transitive visibility grandparent sees grandchild edges`() {
        val gp = Label("gp")
        val p = Label("p")
        val c = Label("c")
        graph.poset.setParents(c, mapOf("up" to p))
        graph.poset.setParents(p, mapOf("up" to gp))
        graph.addNode("a")
        graph.addNode("b")
        graph.addEdge("a", "b", "rel", c)

        val edges = graph.getOutgoingEdges("a", gp).toList()

        assertEquals(1, edges.size)
    }

    @Test
    fun `INFIMUM label sees only INFIMUM-labeled edges`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        graph.addEdge("a", "b", "r1", Label("v1"))
        graph.addEdge("a", "c", "r2", Label.INFIMUM)

        val edges = graph.getOutgoingEdges("a", Label.INFIMUM).toList()

        assertEquals(1, edges.size)
        assertEquals("c", edges.first().dstNid)
    }

    @Test
    fun `setParents with empty map removes all parents`() {
        val label = Label("child")
        val parent = Label("parent")
        graph.poset.setParents(label, mapOf("up" to parent))

        graph.poset.setParents(label, emptyMap())

        assertTrue(graph.poset.getParents(label).isEmpty())
        assertEquals(0, graph.poset.getAncestors(label).count())
    }

    @Test
    fun `allLabels includes user-registered labels`() {
        val l1 = Label("alpha")
        val l2 = Label("beta")
        graph.poset.setParents(l1, mapOf("up" to l2))

        val all = graph.poset.allLabels
        assertTrue(l1 in all)
        assertTrue(l2 in all)
        assertTrue(Label.INFIMUM in all)
        assertTrue(Label.SUPREMUM in all)
    }

    @Test
    fun `filterVisitable comparable labels on separate edges keeps both`() {
        val gp = Label("gp")
        val p = Label("p")
        val c = Label("c")
        graph.poset.setParents(c, mapOf("up" to p))
        graph.poset.setParents(p, mapOf("up" to gp))
        graph.addNode("a")
        graph.addNode("b1")
        graph.addNode("b2")
        graph.addEdge("a", "b1", "shallow", p)
        graph.addEdge("a", "b2", "deep", c)

        val edges = graph.getOutgoingEdges("a", gp).toList()

        assertEquals(2, edges.size, "Pure covering: deeper label (c) is not shadowed by shallower (p)")
    }

    @Test
    fun `filterVisitable INFIMUM edge stays visible beside context edge`() {
        val ctx = Label("ctx")
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        graph.addEdge("a", "b", "seed", Label.INFIMUM)
        graph.addEdge("a", "c", "local", ctx)

        val edges = graph.getOutgoingEdges("a", ctx).toList()

        assertEquals(2, edges.size, "Pure covering: INFIMUM-labeled edge is not shadowed by the context edge")
    }

    @Test
    fun `filterVisitable multiple covered labels on one edge keeps edge`() {
        val gp = Label("gp")
        val p = Label("p")
        val c = Label("c")
        graph.poset.setParents(c, mapOf("up" to p))
        graph.poset.setParents(p, mapOf("up" to gp))
        graph.addNode("a")
        graph.addNode("b")
        graph.addEdge("a", "b", "r1", p)
        graph.addEdge("a", "b", "r1", c)

        val edges = graph.getOutgoingEdges("a", gp).toList()

        assertEquals(1, edges.size)
        val labels = edges.first().labels
        assertTrue(p in labels && c in labels, "Both covered labels remain on the single edge")
    }

    // endregion
}
