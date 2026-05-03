package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.StrVal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-box tests for IPoset (label hierarchy) and PosetTrait (label-filtered graph operations).
 *
 * IPoset (label hierarchy):
 * - `label parents set and get round-trips` — parent assignment
 * - `label parents setter replaces previous parents` — overwrite semantics
 * - `label parents getter returns empty when never set` — default state
 * - `label ancestors multi-level returns all` — transitive BFS
 * - `label ancestors on label with no parents returns empty` — boundary
 * - `compare equal returns zero` — reflexive
 * - `compare child vs parent returns negative` — ordering
 * - `compare parent vs child returns positive` — ordering
 * - `compare incomparable returns null` — no relation
 * - `compare SUPREMUM greater than any` — sentinel
 * - `compare INFIMUM less than any` — sentinel
 * - `compare SUPREMUM vs INFIMUM` — extreme pair
 * - `allLabels includes INFIMUM and SUPREMUM` — sentinels present
 *
 * PosetTrait (label-aware edge operations):
 * - `addEdge with label assigns label` — basic assignment
 * - `addEdge with label existing edge adds label` — accumulation
 * - `addEdge with label same label twice is idempotent` — duplicate label
 * - `addEdge with label missing src throws EntityNotExistException` — guard
 * - `addEdge with label missing dst throws EntityNotExistException` — guard
 * - `delEdge with label removes only that label` — selective removal
 * - `delEdge last label removes edge entirely` — cleanup
 * - `delEdge with label nonexistent edge is no-op` — no-op
 * - `delEdge with label not on edge retains edge` — non-matching
 *
 * PosetTrait (label-filtered traversal):
 * - `getOutgoingEdges with label filters visible edges` — outgoing filter
 * - `getIncomingEdges with label filters visible edges` — incoming filter
 * - `getChildren with label returns visible children` — node filter
 * - `getParents with label returns visible parents` — node filter
 * - `getDescendants with label traverses only visible edges` — BFS filter
 * - `getAncestors with label traverses only visible edges` — BFS filter
 * - `parent label sees child label edges` — visibility rule
 * - `SUPREMUM label sees all edges` — sentinel visibility
 * - `query with label on edges without labels returns empty` — boundary
 * - `multi-level transitive visibility grandparent sees grandchild edges` — deep hierarchy
 * - `getOutgoingEdges with label on node with no edges returns empty` — boundary
 * - `getOutgoingEdges with cond filters by edge predicate` — cond parameter
 * - `getIncomingEdges with cond filters by edge predicate` — cond parameter
 * - `INFIMUM label sees only INFIMUM-labeled edges` — INFIMUM visibility
 * - `setParents with empty map removes all parents` — boundary
 * - `allLabels includes user-registered labels` — registered labels
 * - `getAncestors diamond DAG deduplicates shared ancestors` — DAG dedup
 *
 * Cache and branch coverage:
 * - `compare cache hit forward returns cached result` — cache hit
 * - `compare cache hit reverse returns negated cached result` — reverse cache hit
 * - `filterVisitable multiple visitable labels keeps only maximal` — size>1 path
 * - `addEdge with label on non-existent edge creates then labels` — null path
 * - `label ancestors orphan edge in poset storage skips null intToLabel` — null parent label
 *
 * Regression:
 * - `queryCache survives parents setter during compare sequence` — B4 regression
 */
internal class PosetTraitTest {

    private class TestNode : AbcNode() {
        override val type: AbcNode.Type = object : AbcNode.Type { override val name = "TN" }
    }

    private class TestEdge : AbcEdge() {
        override val type: AbcEdge.Type = object : AbcEdge.Type { override val name = "TE" }
    }

    private class TestGraph :
        AbcSimpleGraph<TestNode, TestEdge>(),
        PosetTrait<TestNode, TestEdge> {
        override val storage = NativeStorageImpl()
        override val graphId: String = "TestPoset"
        val posetStorage = NativeStorageImpl()
        override val poset: IPoset = PosetDftImpl(posetStorage)
        override fun newNodeObj() = TestNode()
        override fun newEdgeObj() = TestEdge()
    }

    private lateinit var graph: TestGraph

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    @AfterTest
    fun tearDown() {
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
    fun `allLabels includes INFIMUM and SUPREMUM`() {
        assertTrue(Label.INFIMUM in graph.poset.allLabels)
        assertTrue(Label.SUPREMUM in graph.poset.allLabels)
    }

    // endregion

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

    // endregion

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
        val e1 = graph.addEdge("a", "b", "calls", label)
        val e2 = graph.addEdge("a", "c", "data", label)

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

    @Test
    fun `filterVisitable multiple visitable labels keeps only maximal`() {
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
        val survivingLabels = edges.first().labels
        assertTrue(p in survivingLabels, "Only maximal label (p) should survive coverage elimination")
    }

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
        val parentId = graph.posetStorage.nodeIDs.first { id ->
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
