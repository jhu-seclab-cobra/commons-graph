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
 * Black-box tests for AbcMultipleGraph: node CRUD and flush guarantees.
 *
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
 * - `flush throws when a cached node is missing from storage` -- verifies fail-fast flush guarantee
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

    // region Flush guarantees

    @Test
    fun `flush throws when a cached node is missing from storage`() {
        graph.addNode(NODE_ID_1)
        val storageId = storage.nodeIDs.single()
        storage.deleteNode(storageId)

        assertFailsWith<EntityNotExistException> { graph.flush() }
    }

    // endregion
}
