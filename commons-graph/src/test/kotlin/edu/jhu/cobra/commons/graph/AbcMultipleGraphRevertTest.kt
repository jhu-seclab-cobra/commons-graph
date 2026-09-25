package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.TestMultipleGraph
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * Black-box tests for AbcMultipleGraph over a LayeredStorageImpl whose active layer is
 * reverted (design-graph.md Revert Coherence; spec-graph.md Revert).
 *
 * - `getNode of node added after freeze is null after revert` -- node evicted from cache
 * - `addNode reuses the ID of a node reverted away` -- ID index freed
 * - `getOutgoingEdges excludes edge added after freeze after revert` -- edge evicted from cache
 * - `getAllNodes excludes node added after freeze after revert` -- node registry follows storage
 * - `delEdge on frozen edge is undone by revert` -- tombstone reverted through the graph
 */
internal class AbcMultipleGraphRevertTest {
    private lateinit var storage: LayeredStorageImpl
    private lateinit var graph: TestMultipleGraph

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
        graph = TestMultipleGraph(storage)
        graph.addNode(GraphFixtures.NODE_ID_1)
        graph.addNode(GraphFixtures.NODE_ID_2)
        graph.addEdge(GraphFixtures.NODE_ID_1, GraphFixtures.NODE_ID_2, GraphFixtures.EDGE_TAG_1)
        storage.freeze()
    }

    @Test
    fun `getNode of node added after freeze is null after revert`() {
        graph.addNode(GraphFixtures.NODE_ID_3)

        storage.clearActiveLayer()

        assertNull(graph.getNode(GraphFixtures.NODE_ID_3))
    }

    @Test
    fun `addNode reuses the ID of a node reverted away`() {
        graph.addNode(GraphFixtures.NODE_ID_3)
        storage.clearActiveLayer()

        graph.addNode(GraphFixtures.NODE_ID_3)

        assertEquals(GraphFixtures.NODE_ID_3, graph.getNode(GraphFixtures.NODE_ID_3)?.id)
    }

    @Test
    fun `getOutgoingEdges excludes edge added after freeze after revert`() {
        graph.addEdge(GraphFixtures.NODE_ID_2, GraphFixtures.NODE_ID_1, GraphFixtures.EDGE_TAG_2)

        storage.clearActiveLayer()

        assertEquals(emptyList(), graph.getOutgoingEdges(GraphFixtures.NODE_ID_2).map { it.eTag }.toList())
    }

    @Test
    fun `getAllNodes excludes node added after freeze after revert`() {
        graph.addNode(GraphFixtures.NODE_ID_3)

        storage.clearActiveLayer()

        assertEquals(
            setOf(GraphFixtures.NODE_ID_1, GraphFixtures.NODE_ID_2),
            graph.getAllNodes().map { it.id }.toSet(),
        )
    }

    @Test
    fun `delEdge on frozen edge is undone by revert`() {
        graph.delEdge(GraphFixtures.NODE_ID_1, GraphFixtures.NODE_ID_2, GraphFixtures.EDGE_TAG_1)

        storage.clearActiveLayer()

        assertEquals(
            listOf(GraphFixtures.EDGE_TAG_1),
            graph.getOutgoingEdges(GraphFixtures.NODE_ID_1).map { it.eTag }.toList(),
        )
    }
}
