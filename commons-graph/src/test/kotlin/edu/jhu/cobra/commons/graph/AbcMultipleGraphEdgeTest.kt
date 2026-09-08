package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_1
import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_2
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
 * Black-box tests for AbcMultipleGraph: edge CRUD with parallel tagged edges.
 *
 * - `addEdge returns edge with correct endpoints` -- verifies addEdge output
 * - `addEdge duplicate same triple throws EntityAlreadyExistException` -- verifies duplicate guard
 * - `addEdge multiple tags same pair allowed` -- verifies parallel edges
 * - `addEdge missing src throws EntityNotExistException` -- verifies src guard
 * - `addEdge missing dst throws EntityNotExistException` -- verifies dst guard
 * - `getEdge existing returns edge` -- verifies retrieval
 * - `getEdge nonexistent returns null` -- verifies absent case
 * - `containEdge existing returns true` -- verifies presence check
 * - `containEdge nonexistent returns false` -- verifies absence check
 * - `delEdge removes edge` -- verifies deletion
 * - `delEdge preserves nodes` -- verifies nodes retained
 * - `delEdge nonexistent is no-op` -- verifies no-op semantics
 * - `getAllEdges returns all edges` -- verifies complete iteration
 * - `getAllEdges with predicate filters` -- verifies predicate filtering
 */
internal class AbcMultipleGraphEdgeTest {
    private lateinit var graph: GraphFixtures.TestMultipleGraph

    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        graph = GraphFixtures.TestMultipleGraph(storage)
    }

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
}
