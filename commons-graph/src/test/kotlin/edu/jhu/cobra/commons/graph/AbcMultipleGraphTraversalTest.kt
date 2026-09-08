package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_1
import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_2
import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_3
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_4
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Black-box tests for AbcMultipleGraph: adjacency and BFS traversal queries.
 *
 * - `getOutgoingEdges returns outgoing` -- verifies outgoing query
 * - `getOutgoingEdges no outgoing returns empty` -- verifies empty case
 * - `getIncomingEdges returns incoming` -- verifies incoming query
 * - `getIncomingEdges no incoming returns empty` -- verifies empty case
 * - `getChildren returns child nodes` -- verifies children query
 * - `getChildren with edge condition filters` -- verifies edgeCond
 * - `getParents returns parent nodes` -- verifies parents query
 * - `getParents with edge condition filters` -- verifies edgeCond
 * - `getDescendants linear chain returns all` -- verifies BFS traversal
 * - `getDescendants with edge condition stops at filtered` -- verifies edgeCond
 * - `getDescendants cycle terminates without duplicates` -- verifies cycle handling
 * - `getDescendants diamond yields each node once` -- verifies no duplicate yields on converging paths
 * - `getDescendants parallel edges yield target once` -- verifies no duplicate yields on parallel edges
 * - `getDescendants cycle does not yield start node` -- verifies start node excluded from its own descendants
 * - `getAncestors linear chain returns all` -- verifies BFS traversal
 * - `getAncestors with edge condition stops at filtered` -- verifies edgeCond
 * - `getAncestors cycle terminates without duplicates` -- verifies cycle handling
 * - `getAncestors cycle does not yield start node` -- verifies start node excluded from its own ancestors
 */
internal class AbcMultipleGraphTraversalTest {
    private lateinit var graph: GraphFixtures.TestMultipleGraph

    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        graph = GraphFixtures.TestMultipleGraph(storage)
    }

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
    fun `getDescendants diamond yields each node once`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)
        graph.addNode(NODE_ID_4)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_1, NODE_ID_3, EDGE_TAG_2)
        graph.addEdge(NODE_ID_2, NODE_ID_4, EDGE_TAG_1)
        graph.addEdge(NODE_ID_3, NODE_ID_4, EDGE_TAG_2)

        val ids = graph.getDescendants(NODE_ID_1).map { it.id }.toList()

        assertEquals(setOf(NODE_ID_2, NODE_ID_3, NODE_ID_4), ids.toSet())
        assertEquals(3, ids.size)
    }

    @Test
    fun `getDescendants parallel edges yield target once`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graph.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_2)

        val ids = graph.getDescendants(NODE_ID_1).map { it.id }.toList()

        assertEquals(listOf(NODE_ID_2), ids)
    }

    @Test
    fun `getDescendants cycle does not yield start node`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "fwd")
        graph.addEdge(NODE_ID_2, NODE_ID_1, "back")

        val descendants = graph.getDescendants(NODE_ID_1).toList()

        assertFalse(descendants.any { it.id == NODE_ID_1 })
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

    @Test
    fun `getAncestors cycle does not yield start node`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "fwd")
        graph.addEdge(NODE_ID_2, NODE_ID_1, "back")

        val ancestors = graph.getAncestors(NODE_ID_1).toList()

        assertFalse(ancestors.any { it.id == NODE_ID_1 })
    }

    // endregion
}
