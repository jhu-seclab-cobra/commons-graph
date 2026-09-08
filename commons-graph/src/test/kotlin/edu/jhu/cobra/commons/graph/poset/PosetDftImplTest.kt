package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.value.StrVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetDftImpl: parent assignment, ancestors, and label registry.
 *
 * - `label parents set and get round-trips` -- parent assignment
 * - `label parents setter replaces previous parents` -- overwrite semantics
 * - `label parents getter returns empty when never set` -- default state
 * - `label ancestors multi-level returns all` -- transitive BFS
 * - `label ancestors on label with no parents returns empty` -- boundary
 * - `allLabels includes INFIMUM and SUPREMUM` -- structural bounds present
 * - `delEdge label on nonexistent edge is no-op`
 * - `label ancestors orphan edge in poset storage skips null intToLabel` -- null parent label
 * - `queryCache survives parents setter during compare sequence` -- B4 regression
 */
internal class PosetDftImplTest {
    private lateinit var graph: TestGraph

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // region IPoset (label hierarchy)

    @Test
    fun `label parents set and get round-trips`() {
        val child = Label("child")
        val parent = Label("parent")

        graph.poset.setParents(child, mapOf("rel" to parent))

        assertEquals(mapOf("rel" to parent), graph.poset.getParents(child))
    }

    @Test
    fun `label parents setter replaces previous parents`() {
        val label = Label("x")
        val p1 = Label("p1")
        val p2 = Label("p2")

        graph.poset.setParents(label, mapOf("a" to p1))
        graph.poset.setParents(label, mapOf("b" to p2))

        assertEquals(mapOf("b" to p2), graph.poset.getParents(label))
        assertFalse(graph.poset.getParents(label).containsKey("a"))
    }

    @Test
    fun `label parents getter returns empty when never set`() {
        assertTrue(graph.poset.getParents(Label("fresh")).isEmpty())
    }

    @Test
    fun `label ancestors multi-level returns all`() {
        val gp = Label("gp")
        val p = Label("p")
        val c = Label("c")

        graph.poset.setParents(c, mapOf("up" to p))
        graph.poset.setParents(p, mapOf("up" to gp))
        val ancestors = graph.poset.getAncestors(c).toSet()
        assertTrue(p in ancestors)
        assertTrue(gp in ancestors)
    }

    @Test
    fun `label ancestors on label with no parents returns empty`() {
        assertEquals(0, graph.poset.getAncestors(Label("orphan")).count())
    }

    @Test
    fun `allLabels includes INFIMUM and SUPREMUM`() {
        assertTrue(Label.INFIMUM in graph.poset.allLabels)
        assertTrue(Label.SUPREMUM in graph.poset.allLabels)
    }

    // endregion

    // region Cache and branch coverage

    @Test
    fun `delEdge label on nonexistent edge is no-op`() {
        graph.addNode("a")
        graph.addNode("b")

        graph.delEdge("a", "b", "nosuch", Label("v1"))

        assertFalse(graph.containEdge("a", "b", "nosuch"))
    }

    @Test
    fun `label ancestors orphan edge in poset storage skips null intToLabel`() {
        val label = Label("root")
        graph.poset.setParents(label, mapOf("up" to Label("parent")))
        val orphanNodeId = graph.posetStorage.addNode()
        val parentId =
            graph.posetStorage.nodeIDs.first { id ->
                (graph.posetStorage.getNodeProperty(id, "label") as? StrVal)?.core == "parent"
            }
        graph.posetStorage.addEdge(parentId, orphanNodeId, "orphan", emptyMap())

        val ancestors = graph.poset.getAncestors(label).toList()
        assertEquals(1, ancestors.size)
        assertEquals(Label("parent"), ancestors.first())
    }

    // endregion

    // region Regression

    @Test
    fun `queryCache survives parents setter during compare sequence`() {
        val la = Label("A")
        val lb = Label("B")
        val lc = Label("C")

        graph.poset.setParents(la, mapOf("p" to lb))
        graph.poset.setParents(lb, mapOf("p" to lc))
        assertEquals(-1, graph.poset.compare(la, lc))

        graph.poset.setParents(la, mapOf("p" to lb, "q" to lc))

        assertEquals(-1, graph.poset.compare(la, lc))
    }

    // endregion
}
