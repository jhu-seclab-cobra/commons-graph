package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
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
 * Black-box tests for TraitPoset: IPoset implementation, label-filtered graph
 * operations, and edge visibility rules.
 *
 * IPoset:
 * - `label parents set and get round-trips` — parent assignment
 * - `label parents setter replaces previous parents` — overwrite semantics
 * - `label parents getter returns empty when never set` — default state
 * - `label ancestors multi-level returns all` — transitive BFS
 * - `label ancestors on label with no parents returns empty` — boundary
 * - `label compareTo equal returns zero` — reflexive
 * - `label compareTo child vs parent returns negative` — ordering
 * - `label compareTo parent vs child returns positive` — ordering
 * - `label compareTo incomparable returns null` — no relation
 * - `label compareTo SUPREMUM greater than any` — sentinel
 * - `label compareTo INFIMUM less than any` — sentinel
 * - `label compareTo SUPREMUM vs INFIMUM` — extreme pair
 * - `allLabels includes INFIMUM and SUPREMUM` — sentinels present
 *
 * Label-aware edge operations:
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
 * Label-filtered traversal:
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
 * - `queryCache survives parents setter during compareTo sequence` — B4 regression
 *
 * Cache and branch coverage:
 * - `compareTo cache hit forward returns cached result` — line 112 cache hit
 * - `compareTo cache hit reverse returns negated cached result` — line 114 reverse cache hit
 * - `filterVisitable multiple visitable labels keeps only maximal` — line 145 size>1 path
 * - `addEdge with label on non-existent edge creates then labels` — line 161 null path
 * - `ensureCache already ready skips initialization` — cacheReady true early return
 * - `resolveLabelId unknown label returns null` — label not in cache
 * - `ensureLabelNode existing label returns cached ID` — label already in cache
 * - `doFilterVisitable edge with no labels returns empty` — empty labels set
 * - `doFilterVisitable allVisitable empty returns empty` — no labels match by
 * - `delEdge label on nonexistent edge is no-op` — getEdge returns null early return
 * - `label ancestors orphan edge in poset storage skips null intToLabel` — null parent label
 */
internal class TraitPosetTest {

    private class TestNode : AbcNode() {
        override val type: AbcNode.Type = object : AbcNode.Type { override val name = "TN" }
    }

    private class TestEdge : AbcEdge() {
        override val type: AbcEdge.Type = object : AbcEdge.Type { override val name = "TE" }
    }

    private class TestGraph :
        AbcSimpleGraph<TestNode, TestEdge>(),
        TraitPoset<TestNode, TestEdge> {
        override val storage = NativeStorageImpl()
        override val posetStorage = NativeStorageImpl()
        override val posetState = TraitPoset.PosetState()
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
        graph.close()
    }

    // region IPoset

    @Test
    fun `label parents set and get round-trips`() {
        val child = Label("child")
        val parent = Label("parent")

        with(graph) { child.parents = mapOf("rel" to parent) }

        with(graph) { assertEquals(mapOf("rel" to parent), child.parents) }
    }

    @Test
    fun `label parents setter replaces previous parents`() {
        val label = Label("x")
        val p1 = Label("p1")
        val p2 = Label("p2")

        with(graph) {
            label.parents = mapOf("a" to p1)
            label.parents = mapOf("b" to p2)

            assertEquals(mapOf("b" to p2), label.parents)
            assertFalse(label.parents.containsKey("a"))
        }
    }

    @Test
    fun `label parents getter returns empty when never set`() {
        val label = Label("fresh")

        with(graph) { assertTrue(label.parents.isEmpty()) }
    }

    @Test
    fun `label ancestors multi-level returns all`() {
        val gp = Label("gp")
        val p = Label("p")
        val c = Label("c")

        with(graph) {
            c.parents = mapOf("up" to p)
            p.parents = mapOf("up" to gp)
            val ancestors = c.ancestors.toSet()
            assertTrue(p in ancestors)
            assertTrue(gp in ancestors)
        }
    }

    @Test
    fun `label ancestors on label with no parents returns empty`() {
        val label = Label("orphan")

        with(graph) { assertEquals(0, label.ancestors.count()) }
    }

    @Test
    fun `label compareTo equal returns zero`() {
        val label = Label("same")

        with(graph) { assertEquals(0, label.compareTo(label)) }
    }

    @Test
    fun `label compareTo child vs parent returns negative`() {
        val parent = Label("parent")
        val child = Label("child")

        with(graph) {
            child.parents = mapOf("up" to parent)
            val result = child.compareTo(parent)
            assertNotNull(result)
            assertTrue(result < 0)
        }
    }

    @Test
    fun `label compareTo parent vs child returns positive`() {
        val parent = Label("parent")
        val child = Label("child")

        with(graph) {
            child.parents = mapOf("up" to parent)
            val result = parent.compareTo(child)
            assertNotNull(result)
            assertTrue(result > 0)
        }
    }

    @Test
    fun `label compareTo incomparable returns null`() {
        val a = Label("a")
        val b = Label("b")

        with(graph) { assertNull(a.compareTo(b)) }
    }

    @Test
    fun `label compareTo SUPREMUM greater than any`() {
        val label = Label("any")

        with(graph) {
            assertEquals(1, Label.SUPREMUM.compareTo(label))
            assertEquals(-1, label.compareTo(Label.SUPREMUM))
        }
    }

    @Test
    fun `label compareTo INFIMUM less than any`() {
        val label = Label("any")

        with(graph) {
            assertEquals(-1, Label.INFIMUM.compareTo(label))
            assertEquals(1, label.compareTo(Label.INFIMUM))
        }
    }

    @Test
    fun `label compareTo SUPREMUM vs INFIMUM`() {
        with(graph) {
            assertEquals(1, Label.SUPREMUM.compareTo(Label.INFIMUM))
            assertEquals(-1, Label.INFIMUM.compareTo(Label.SUPREMUM))
        }
    }

    @Test
    fun `allLabels includes INFIMUM and SUPREMUM`() {
        with(graph) {
            assertTrue(Label.INFIMUM in allLabels)
            assertTrue(Label.SUPREMUM in allLabels)
        }
    }

    // endregion

    // region Label-aware edge operations

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

    // region Label-filtered traversal

    @Test
    fun `getOutgoingEdges with label filters visible edges`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        val l1 = Label("v1")
        val l2 = Label("v2")
        graph.addEdge("a", "b", "r1", l1)
        graph.addEdge("a", "c", "r2", l2)

        val edges = graph.getOutgoingEdges("a", l1).toList()

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
        with(graph) { child.parents = mapOf("up" to parent) }
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
        with(graph) {
            c.parents = mapOf("up" to p)
            p.parents = mapOf("up" to gp)
        }
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

    // endregion

    // region Cache and branch coverage

    @Test
    fun `compareTo cache hit forward returns cached result`() {
        val parent = Label("parent")
        val child = Label("child")

        with(graph) {
            child.parents = mapOf("up" to parent)
            val first = child.compareTo(parent)
            val second = child.compareTo(parent)
            assertEquals(first, second)
            assertNotNull(second)
            assertTrue(second < 0)
        }
    }

    @Test
    fun `compareTo cache hit reverse returns negated cached result`() {
        val parent = Label("parent")
        val child = Label("child")

        with(graph) {
            child.parents = mapOf("up" to parent)
            val forward = child.compareTo(parent)
            val reverse = parent.compareTo(child)
            assertNotNull(forward)
            assertNotNull(reverse)
            assertEquals(-forward, reverse)
        }
    }

    @Test
    fun `filterVisitable multiple visitable labels keeps only maximal`() {
        val gp = Label("gp")
        val p = Label("p")
        val c = Label("c")
        with(graph) {
            c.parents = mapOf("up" to p)
            p.parents = mapOf("up" to gp)
        }
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
    fun `ensureCache already ready skips initialization`() {
        with(graph) {
            val first = allLabels
            val second = allLabels
            assertEquals(first, second)
        }
    }

    @Test
    fun `resolveLabelId unknown label returns null`() {
        val result = graph.posetState.resolveLabelId(Label("nonexistent"), graph.posetStorage)

        assertNull(result)
    }

    @Test
    fun `ensureLabelNode existing label returns cached ID`() {
        val label = Label("existing")
        val firstId = graph.posetState.ensureLabelNode(label, graph.posetStorage)

        val secondId = graph.posetState.ensureLabelNode(label, graph.posetStorage)

        assertEquals(firstId, secondId)
    }

    @Test
    fun `doFilterVisitable edge with no labels returns empty`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addEdge("a", "b", "rel")

        val edges = graph.doFilterVisitable(
            graph.getOutgoingEdges("a"),
            Label("v1"),
        ).toList()

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `doFilterVisitable allVisitable empty returns empty`() {
        graph.addNode("a")
        graph.addNode("b")
        graph.addEdge("a", "b", "rel", Label("unrelated"))

        val edges = graph.doFilterVisitable(
            graph.getOutgoingEdges("a"),
            Label("query"),
        ).toList()

        assertTrue(edges.isEmpty())
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
        with(graph) { label.parents = mapOf("up" to Label("parent")) }
        val parentId = graph.posetState.resolveLabelId(Label("parent"), graph.posetStorage)!!
        val orphanNodeId = graph.posetStorage.addNode()
        graph.posetStorage.addEdge(parentId, orphanNodeId, "orphan", emptyMap())

        with(graph) {
            val ancestors = label.ancestors.toList()
            assertEquals(1, ancestors.size)
            assertEquals(Label("parent"), ancestors.first())
        }
    }

    // endregion

    // region Regression

    @Test
    fun `queryCache survives parents setter during compareTo sequence`() {
        val la = Label("A")
        val lb = Label("B")
        val lc = Label("C")

        with(graph) {
            la.parents = mapOf("p" to lb)
            lb.parents = mapOf("p" to lc)
            assertEquals(-1, la.compareTo(lc))

            la.parents = mapOf("p" to lb, "q" to lc)

            assertEquals(-1, la.compareTo(lc))
        }
    }

    // endregion
}
