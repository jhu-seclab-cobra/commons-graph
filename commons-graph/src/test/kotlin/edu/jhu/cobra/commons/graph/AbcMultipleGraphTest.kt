package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_1
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_3
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for AbcMultipleGraph: node CRUD.
 *
 * - `texts of the same bytes are one node ID` -- verifies the node ID is its canonical text in every lookup
 * - `claim and read register the canonical text of the identity` -- verifies claimNode and readNode
 * - `a node ID holding a lone surrogate outside the byte units is its own ID` -- verifies such an ID stays distinct
 * - `addNode returns node with correct id` -- verifies addNode output
 * - `addNode registers id in nodeIDs` -- verifies nodeIDs updated
 * - `addNode duplicate throws EntityAlreadyExistException` -- verifies duplicate guard
 * - `getNode existing returns node` -- verifies retrieval
 * - `getNode nonexistent returns null` -- verifies absent case
 * - `containNode existing returns true` -- verifies presence check
 * - `containNode nonexistent returns false` -- verifies absence check
 * - `delNode removes node` -- verifies deletion
 * - `delNode removes associated edges` -- verifies cascade
 * - `delNode nonexistent is no-op` -- verifies no-op semantics
 * - `getAllNodes returns all nodes` -- verifies complete iteration
 * - `getAllNodes with predicate filters` -- verifies predicate filtering
 */
internal class AbcMultipleGraphTest {
    private lateinit var graph: GraphFixtures.TestMultipleGraph

    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        graph = GraphFixtures.TestMultipleGraph(storage)
    }

    // region Node CRUD

    @Test
    fun `texts of the same bytes are one node ID`() {
        val node = graph.addNode("\uDCC3\uDCA9")

        assertEquals("é", node.id)
        assertEquals(setOf("é"), graph.nodeIDs)
        assertEquals("é", storage.getNodeProperty(node.storageId, AbcMultipleGraph.PROP_NODE_ID)?.core)
        assertFailsWith<EntityAlreadyExistException> { graph.addNode("é") }
        assertEquals(node, graph.getNode("é"))
        assertEquals(node, graph.getNode("\uDCC3\uDCA9"))
        assertTrue(graph.containNode("\uDCC3\uDCA9"))
        graph.addNode(NODE_ID_2)
        graph.addEdge("\uDCC3\uDCA9", NODE_ID_2, EDGE_TAG_1)
        assertNotNull(graph.getEdge("é", NODE_ID_2, EDGE_TAG_1))
        assertEquals("é", graph.getOutgoingEdges("\uDCC3\uDCA9").single().srcNid)
    }

    @Test
    fun `claim and read register the canonical text of the identity`() {
        val source = graph.addNode("é")
        val identity =
            object : NodeIdentity {
                override val id = "\uDCC3\uDCA9"
                override val storageId = source.storageId
            }
        assertEquals("é", graph.readNode(identity).id)
        val receiver = GraphFixtures.TestMultipleGraph(storage)
        assertEquals("é", receiver.claimNode(identity).id)
        assertEquals(setOf("é"), receiver.nodeIDs)
        assertEquals("é", GraphFixtures.TestMultipleGraph(storage).readNode(identity).id)
    }

    @Test
    fun `a node ID holding a lone surrogate outside the byte units is its own ID`() {
        assertEquals("n\uD800", graph.addNode("n\uD800").id)
        graph.addNode("n?")
        assertEquals(setOf("n\uD800", "n?"), graph.nodeIDs)
        assertEquals("n\uD800", graph.getNode("n\uD800")?.id)
        assertEquals("\uDC80", graph.addNode("\uDC80").id)
    }

    @Test
    fun `row lookup resolves only this graph's registered identity`() {
        val node = graph.addNode("opaque/source")
        val other = GraphFixtures.TestMultipleGraph(storage).addNode("opaque/other")
        assertEquals(node.id, graph.resolveNodeId(node.storageId))
        assertNull(graph.resolveNodeId(other.storageId))
        assertEquals(setOf(node.id), graph.nodeIDs)
        graph.delNode(node.id)
        assertNull(graph.resolveNodeId(node.storageId))
    }

    @Test
    fun `claim reads an existing identity without a writable node`() {
        val source = graph.addNode(NODE_ID_1)
        val identity = object : NodeIdentity {
            override val id = source.id
            override val storageId = source.storageId
        }
        val receiver = GraphFixtures.TestMultipleGraph(storage)
        val claimed = receiver.claimNode(identity)
        assertEquals(source.id, claimed.id)
        assertEquals(source.storageId, claimed.storageId)
        assertEquals(setOf(source.id), receiver.nodeIDs)
        assertEquals(claimed, receiver.claimNode(identity))
        assertEquals(1, storage.nodeIDs.size)
    }

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
}
