package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.EDGE_TAG_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.EDGE_TAG_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.EDGE_TAG_3
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_4
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
 * Black-box tests for AbcMultipleGraph: node/edge CRUD and structure queries.
 * Label-aware operations tested in TraitPosetTest.
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
 */
internal class AbcMultipleGraphTest {
    private lateinit var graph: GraphTestUtils.TestMultipleGraph
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        graph = GraphTestUtils.TestMultipleGraph(storage)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
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
}
