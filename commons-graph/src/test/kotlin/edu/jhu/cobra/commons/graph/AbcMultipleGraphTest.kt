package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.EDGE_TAG_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.EDGE_TAG_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.EDGE_TAG_3
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_4
import edu.jhu.cobra.commons.graph.GraphTestUtils.createTestMultipleGraph
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
 * Black-box tests for AbcMultipleGraph: node/edge CRUD, structure queries,
 * label-aware operations, exceptions, IPoset integration.
 *
 * - `addNode returns node with correct id` — verifies addNode output
 * - `addNode registers id in nodeIDs` — verifies nodeIDs updated
 * - `addNode duplicate throws EntityAlreadyExistException` — verifies duplicate guard
 * - `getNode existing returns node` — verifies retrieval
 * - `getNode nonexistent returns null` — verifies absent case
 * - `containNode existing returns true` — verifies presence check
 * - `containNode nonexistent returns false` — verifies absence check
 * - `delNode removes node` — verifies deletion
 * - `delNode removes associated edges` — verifies cascade
 * - `delNode nonexistent is no-op` — verifies no-op semantics
 * - `getAllNodes returns all nodes` — verifies complete iteration
 * - `getAllNodes with predicate filters` — verifies predicate filtering
 * - `addEdge returns edge with correct endpoints` — verifies addEdge output
 * - `addEdge duplicate same triple throws EntityAlreadyExistException` — verifies duplicate guard
 * - `addEdge multiple tags same pair allowed` — verifies parallel edges
 * - `addEdge missing src throws EntityNotExistException` — verifies src guard
 * - `addEdge missing dst throws EntityNotExistException` — verifies dst guard
 * - `getEdge existing returns edge` — verifies retrieval
 * - `getEdge nonexistent returns null` — verifies absent case
 * - `containEdge existing returns true` — verifies presence check
 * - `containEdge nonexistent returns false` — verifies absence check
 * - `delEdge removes edge` — verifies deletion
 * - `delEdge preserves nodes` — verifies nodes retained
 * - `delEdge nonexistent is no-op` — verifies no-op semantics
 * - `getAllEdges returns all edges` — verifies complete iteration
 * - `getAllEdges with predicate filters` — verifies predicate filtering
 * - `getOutgoingEdges returns outgoing` — verifies outgoing query
 * - `getOutgoingEdges no outgoing returns empty` — verifies empty case
 * - `getIncomingEdges returns incoming` — verifies incoming query
 * - `getIncomingEdges no incoming returns empty` — verifies empty case
 * - `getChildren returns child nodes` — verifies children query
 * - `getChildren with edge condition filters` — verifies edgeCond
 * - `getParents returns parent nodes` — verifies parents query
 * - `getParents with edge condition filters` — verifies edgeCond
 * - `getDescendants linear chain returns all` — verifies BFS traversal
 * - `getDescendants with edge condition stops at filtered` — verifies edgeCond
 * - `getDescendants cycle terminates without duplicates` — verifies cycle handling
 * - `getAncestors linear chain returns all` — verifies BFS traversal
 * - `getAncestors with edge condition stops at filtered` — verifies edgeCond
 * - `getAncestors cycle terminates without duplicates` — verifies cycle handling
 * - `addEdge with label assigns label` — verifies label assignment
 * - `addEdge with label existing edge adds label` — verifies label accumulation
 * - `addEdge with label missing src throws EntityNotExistException` — verifies guard
 * - `addEdge with label missing dst throws EntityNotExistException` — verifies guard
 * - `delEdge with label removes only that label` — verifies selective removal
 * - `delEdge last label removes edge entirely` — verifies edge cleanup
 * - `delEdge with label nonexistent edge is no-op` — verifies no-op
 * - `delEdge with label not on edge retains edge` — verifies non-matching label
 * - `label parents set and get round-trips` — verifies IPoset parents
 * - `label ancestors multi-level returns all` — verifies BFS ancestors
 * - `label compareTo equal returns zero` — verifies reflexive comparison
 * - `label compareTo child vs parent returns negative` — verifies ordering
 * - `label compareTo parent vs child returns positive` — verifies ordering
 * - `label compareTo incomparable returns null` — verifies incomparability
 * - `label compareTo SUPREMUM greater than any` — verifies SUPREMUM bound
 * - `label compareTo INFIMUM less than any` — verifies INFIMUM bound
 * - `allLabels includes INFIMUM and SUPREMUM` — verifies sentinel presence
 * - `getOutgoingEdges with label filters visible edges` — verifies label filtering
 * - `getIncomingEdges with label filters visible edges` — verifies label filtering
 * - `getChildren with label returns visible children` — verifies label filtering
 * - `getParents with label returns visible parents` — verifies label filtering
 * - `getDescendants with label traverses only visible edges` — verifies label filtering
 * - `getAncestors with label traverses only visible edges` — verifies label filtering
 * - `parent label sees child label edges` — verifies visibility rule
 */
internal class AbcMultipleGraphTest {
    private lateinit var graph: AbcMultipleGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>
    private lateinit var storage: NativeStorageImpl
    private lateinit var posetStorage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        posetStorage = NativeStorageImpl()
        graph = createTestMultipleGraph(storage, posetStorage)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        posetStorage.close()
    }

    // region Node CRUD

    @Test
    fun `addNode returns node with correct id`() {
        val node = graph.addNode(NODE_ID_1)

        assertEquals(NODE_ID_1, node.id)
    }

    @Test
    fun `addNode registers id in nodeIDs`() {
        graph.addNode(NODE_ID_1)

        assertTrue(graph.nodeIDs.contains(NODE_ID_1))
    }

    @Test
    fun `addNode duplicate throws EntityAlreadyExistException`() {
        graph.addNode(NODE_ID_1)

        assertFailsWith<EntityAlreadyExistException> { graph.addNode(NODE_ID_1) }
    }

    @Test
    fun `getNode existing returns node`() {
        graph.addNode(NODE_ID_1)

        val node = graph.getNode(NODE_ID_1)

        assertNotNull(node)
        assertEquals(NODE_ID_1, node.id)
    }

    @Test
    fun `getNode nonexistent returns null`() {
        assertNull(graph.getNode(NODE_ID_1))
    }

    @Test
    fun `containNode existing returns true`() {
        graph.addNode(NODE_ID_1)

        assertTrue(graph.containNode(NODE_ID_1))
    }

    @Test
    fun `containNode nonexistent returns false`() {
        assertFalse(graph.containNode(NODE_ID_1))
    }

    @Test
    fun `delNode removes node`() {
        graph.addNode(NODE_ID_1)

        graph.delNode(NODE_ID_1)

        assertFalse(graph.containNode(NODE_ID_1))
    }

    @Test
    fun `delNode removes associated edges`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        graph.delNode(NODE_ID_1)

        assertFalse(graph.containEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1))
        assertTrue(graph.containNode(NODE_ID_2))
    }

    @Test
    fun `delNode nonexistent is no-op`() {
        graph.delNode(NODE_ID_1)

        assertFalse(graph.containNode(NODE_ID_1))
    }

    @Test
    fun `getAllNodes returns all nodes`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)

        val ids = graph.getAllNodes().map { it.id }.toSet()

        assertEquals(setOf(NODE_ID_1, NODE_ID_2, NODE_ID_3), ids)
    }

    @Test
    fun `getAllNodes with predicate filters`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        val ids = graph.getAllNodes { it.id == NODE_ID_1 }.map { it.id }.toList()

        assertEquals(listOf(NODE_ID_1), ids)
    }

    // endregion

    // region Edge CRUD

    @Test
    fun `addEdge returns edge with correct endpoints`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        assertEquals(NODE_ID_1, edge.srcNid)
        assertEquals(NODE_ID_2, edge.dstNid)
        assertEquals(EDGE_TAG_1, edge.eTag)
    }

    @Test
    fun `addEdge duplicate same triple throws EntityAlreadyExistException`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        }
    }

    @Test
    fun `addEdge multiple tags same pair allowed`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_2)

        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1))
        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_2))
    }

    @Test
    fun `addEdge missing src throws EntityNotExistException`() {
        graph.addNode(NODE_ID_2)

        assertFailsWith<EntityNotExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        }
    }

    @Test
    fun `addEdge missing dst throws EntityNotExistException`() {
        graph.addNode(NODE_ID_1)

        assertFailsWith<EntityNotExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        }
    }

    @Test
    fun `getEdge existing returns edge`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        val edge = graph.getEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        assertNotNull(edge)
        assertEquals("$NODE_ID_1-$EDGE_TAG_1-$NODE_ID_2", edge.id)
    }

    @Test
    fun `getEdge nonexistent returns null`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        assertNull(graph.getEdge(NODE_ID_1, NODE_ID_2, "missing"))
    }

    @Test
    fun `containEdge existing returns true`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1))
    }

    @Test
    fun `containEdge nonexistent returns false`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        assertFalse(graph.containEdge(NODE_ID_1, NODE_ID_2, "missing"))
    }

    @Test
    fun `delEdge removes edge`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        graph.delEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        assertFalse(graph.containEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1))
    }

    @Test
    fun `delEdge preserves nodes`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        graph.delEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        assertTrue(graph.containNode(NODE_ID_1))
        assertTrue(graph.containNode(NODE_ID_2))
    }

    @Test
    fun `delEdge nonexistent is no-op`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        graph.delEdge(NODE_ID_1, NODE_ID_2, "missing")

        assertFalse(graph.containEdge(NODE_ID_1, NODE_ID_2, "missing"))
    }

    @Test
    fun `getAllEdges returns all edges`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_2, NODE_ID_3, EDGE_TAG_2)

        assertEquals(2, graph.getAllEdges().count())
    }

    @Test
    fun `getAllEdges with predicate filters`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_2, NODE_ID_3, EDGE_TAG_2)

        val filtered = graph.getAllEdges { it.srcNid == NODE_ID_1 }.toList()

        assertEquals(1, filtered.size)
        assertEquals(NODE_ID_1, filtered.first().srcNid)
    }

    // endregion

    // region Structure queries

    @Test
    fun `getOutgoingEdges returns outgoing`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_1, NODE_ID_3, EDGE_TAG_2)

        val dsts = graph.getOutgoingEdges(NODE_ID_1).map { it.dstNid }.toSet()

        assertEquals(setOf(NODE_ID_2, NODE_ID_3), dsts)
    }

    @Test
    fun `getOutgoingEdges no outgoing returns empty`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        assertTrue(graph.getOutgoingEdges(NODE_ID_2).toList().isEmpty())
    }

    @Test
    fun `getIncomingEdges returns incoming`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_3, EDGE_TAG_1)
        graph.addEdge(NODE_ID_2, NODE_ID_3, EDGE_TAG_2)

        val srcs = graph.getIncomingEdges(NODE_ID_3).map { it.srcNid }.toSet()

        assertEquals(setOf(NODE_ID_1, NODE_ID_2), srcs)
    }

    @Test
    fun `getIncomingEdges no incoming returns empty`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        assertTrue(graph.getIncomingEdges(NODE_ID_1).toList().isEmpty())
    }

    @Test
    fun `getChildren returns child nodes`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_1, NODE_ID_3, EDGE_TAG_2)

        val ids = graph.getChildren(NODE_ID_1).map { it.id }.toSet()

        assertEquals(setOf(NODE_ID_2, NODE_ID_3), ids)
    }

    @Test
    fun `getChildren with edge condition filters`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "typeA")
        graph.addEdge(NODE_ID_1, NODE_ID_3, "typeB")

        val ids = graph.getChildren(NODE_ID_1) { it.eTag == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(NODE_ID_2), ids)
    }

    @Test
    fun `getParents returns parent nodes`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_3, EDGE_TAG_1)
        graph.addEdge(NODE_ID_2, NODE_ID_3, EDGE_TAG_2)

        val ids = graph.getParents(NODE_ID_3).map { it.id }.toSet()

        assertEquals(setOf(NODE_ID_1, NODE_ID_2), ids)
    }

    @Test
    fun `getParents with edge condition filters`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_3, "typeA")
        graph.addEdge(NODE_ID_2, NODE_ID_3, "typeB")

        val ids = graph.getParents(NODE_ID_3) { it.eTag == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(NODE_ID_1), ids)
    }

    @Test
    fun `getDescendants linear chain returns all`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addNode(NODE_ID_4)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_2, NODE_ID_3, EDGE_TAG_2)
        graph.addEdge(NODE_ID_3, NODE_ID_4, EDGE_TAG_3)

        val ids = graph.getDescendants(NODE_ID_1).map { it.id }.toSet()

        assertEquals(setOf(NODE_ID_2, NODE_ID_3, NODE_ID_4), ids)
    }

    @Test
    fun `getDescendants with edge condition stops at filtered`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "typeA")
        graph.addEdge(NODE_ID_2, NODE_ID_3, "typeB")

        val ids = graph.getDescendants(NODE_ID_1) { it.eTag == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(NODE_ID_2), ids)
    }

    @Test
    fun `getDescendants cycle terminates without duplicates`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "fwd")
        graph.addEdge(NODE_ID_2, NODE_ID_1, "back")

        val descendants = graph.getDescendants(NODE_ID_1).toList()

        assertTrue(descendants.any { it.id == NODE_ID_2 })
        assertEquals(descendants.distinctBy { it.id }.size, descendants.size)
    }

    @Test
    fun `getAncestors linear chain returns all`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addNode(NODE_ID_4)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_2, NODE_ID_3, EDGE_TAG_2)
        graph.addEdge(NODE_ID_3, NODE_ID_4, EDGE_TAG_3)

        val ids = graph.getAncestors(NODE_ID_4).map { it.id }.toSet()

        assertEquals(setOf(NODE_ID_1, NODE_ID_2, NODE_ID_3), ids)
    }

    @Test
    fun `getAncestors with edge condition stops at filtered`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "typeA")
        graph.addEdge(NODE_ID_2, NODE_ID_3, "typeB")

        val ids = graph.getAncestors(NODE_ID_3) { it.eTag == "typeB" }.map { it.id }.toList()

        assertEquals(listOf(NODE_ID_2), ids)
    }

    @Test
    fun `getAncestors cycle terminates without duplicates`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "fwd")
        graph.addEdge(NODE_ID_2, NODE_ID_1, "back")

        val ancestors = graph.getAncestors(NODE_ID_1).toList()

        assertTrue(ancestors.any { it.id == NODE_ID_2 })
        assertEquals(ancestors.distinctBy { it.id }.size, ancestors.size)
    }

    // endregion

    // region Label-aware edge operations

    @Test
    fun `addEdge with label assigns label`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val label = Label("v1")

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, label)

        assertTrue(label in edge.labels)
    }

    @Test
    fun `addEdge with label existing edge adds label`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val labelA = Label("a")
        val labelB = Label("b")
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, labelA)

        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, labelB)

        val edge = graph.getEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)!!
        assertTrue(edge.labels.containsAll(setOf(labelA, labelB)))
    }

    @Test
    fun `addEdge with label missing src throws EntityNotExistException`() {
        graph.addNode(NODE_ID_2)

        assertFailsWith<EntityNotExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, Label("v1"))
        }
    }

    @Test
    fun `addEdge with label missing dst throws EntityNotExistException`() {
        graph.addNode(NODE_ID_1)

        assertFailsWith<EntityNotExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, Label("v1"))
        }
    }

    @Test
    fun `delEdge with label removes only that label`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val labelA = Label("a")
        val labelB = Label("b")
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, labelA)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, labelB)

        graph.delEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, labelA)

        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1))
        val edge = graph.getEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)!!
        assertFalse(labelA in edge.labels)
        assertTrue(labelB in edge.labels)
    }

    @Test
    fun `delEdge last label removes edge entirely`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val label = Label("only")
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, label)

        graph.delEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, label)

        assertFalse(graph.containEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1))
    }

    @Test
    fun `delEdge with label nonexistent edge is no-op`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        graph.delEdge(NODE_ID_1, NODE_ID_2, "missing", Label("v1"))

        assertFalse(graph.containEdge(NODE_ID_1, NODE_ID_2, "missing"))
    }

    @Test
    fun `delEdge with label not on edge retains edge`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val labelA = Label("a")
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, labelA)

        graph.delEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, Label("unrelated"))

        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1))
        assertTrue(labelA in graph.getEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)!!.labels)
    }

    // endregion

    // region IPoset integration

    @Test
    fun `label parents set and get round-trips`() {
        val child = Label("child")
        val parent = Label("parent")

        with(graph) {
            child.parents = mapOf("rel" to parent)

            assertEquals(mapOf("rel" to parent), child.parents)
        }
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
    fun `label compareTo equal returns zero`() {
        val label = Label("same")

        with(graph) {
            assertEquals(0, label.compareTo(label))
        }
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

        with(graph) {
            assertNull(a.compareTo(b))
        }
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
    fun `allLabels includes INFIMUM and SUPREMUM`() {
        with(graph) {
            assertTrue(Label.INFIMUM in allLabels)
            assertTrue(Label.SUPREMUM in allLabels)
        }
    }

    // endregion

    // region Label-filtered traversal

    @Test
    fun `getOutgoingEdges with label filters visible edges`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        val labelA = Label("a")
        val labelB = Label("b")
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel1", labelA)
        graph.addEdge(NODE_ID_1, NODE_ID_3, "rel2", labelB)

        val edges = graph.getOutgoingEdges(NODE_ID_1, labelA).toList()

        assertEquals(1, edges.size)
        assertEquals(NODE_ID_2, edges.first().dstNid)
    }

    @Test
    fun `getIncomingEdges with label filters visible edges`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        val label = Label("v1")
        graph.addEdge(NODE_ID_1, NODE_ID_3, "rel1", label)
        graph.addEdge(NODE_ID_2, NODE_ID_3, "rel2", Label("other"))

        val edges = graph.getIncomingEdges(NODE_ID_3, label).toList()

        assertEquals(1, edges.size)
        assertEquals(NODE_ID_1, edges.first().srcNid)
    }

    @Test
    fun `getChildren with label returns visible children`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        val label = Label("v1")
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel1", label)
        graph.addEdge(NODE_ID_1, NODE_ID_3, "rel2", Label("other"))

        val ids = graph.getChildren(NODE_ID_1, label).map { it.id }.toList()

        assertEquals(listOf(NODE_ID_2), ids)
    }

    @Test
    fun `getParents with label returns visible parents`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        val label = Label("v1")
        graph.addEdge(NODE_ID_1, NODE_ID_3, "rel1", label)
        graph.addEdge(NODE_ID_2, NODE_ID_3, "rel2", Label("other"))

        val ids = graph.getParents(NODE_ID_3, label).map { it.id }.toList()

        assertEquals(listOf(NODE_ID_1), ids)
    }

    @Test
    fun `getDescendants with label traverses only visible edges`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        val label = Label("v1")
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel1", label)
        graph.addEdge(NODE_ID_2, NODE_ID_3, "rel2", Label("other"))

        val ids = graph.getDescendants(NODE_ID_1, label).map { it.id }.toList()

        assertEquals(listOf(NODE_ID_2), ids)
    }

    @Test
    fun `getAncestors with label traverses only visible edges`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        val label = Label("v1")
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel1", Label("other"))
        graph.addEdge(NODE_ID_2, NODE_ID_3, "rel2", label)

        val ids = graph.getAncestors(NODE_ID_3, label).map { it.id }.toList()

        assertEquals(listOf(NODE_ID_2), ids)
    }

    @Test
    fun `parent label sees child label edges`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val parent = Label("parent")
        val child = Label("child")
        with(graph) { child.parents = mapOf("up" to parent) }
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1, child)

        val edges = graph.getOutgoingEdges(NODE_ID_1, parent).toList()

        assertEquals(1, edges.size)
    }

    // endregion
}
