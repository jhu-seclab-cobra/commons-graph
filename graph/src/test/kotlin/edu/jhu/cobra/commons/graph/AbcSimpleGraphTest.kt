package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.TestSimpleGraph
import edu.jhu.cobra.commons.graph.GraphTestUtils.createTestSimpleGraph
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId1
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId2
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId3
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

class AbcSimpleGraphTest {
    private lateinit var graph: AbcSimpleGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = createTestSimpleGraph(storage)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // region Simple graph constraint

    @Test
    fun `test addEdge_firstEdgeBetweenNodes_succeeds`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edgeId = EdgeID(nodeId1, nodeId2, "rel")

        val edge = graph.addEdge(edgeId)

        assertEquals(edgeId, edge.id)
        assertTrue(graph.containEdge(edgeId))
    }

    @Test
    fun `test addEdge_duplicateSameNodePairSameType_throwsEntityAlreadyExist`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edgeId = EdgeID(nodeId1, nodeId2, "rel")
        graph.addEdge(edgeId)

        assertFailsWith<EntityAlreadyExistException> { graph.addEdge(edgeId) }
    }

    @Test
    fun `test addEdge_sameNodePairDifferentType_throwsEntityAlreadyExist`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(EdgeID(nodeId1, nodeId2, "typeA"))

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(EdgeID(nodeId1, nodeId2, "typeB"))
        }
    }

    @Test
    fun `test addEdge_reverseDirection_allowed`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val fwd = EdgeID(nodeId1, nodeId2, "rel")
        val rev = EdgeID(nodeId2, nodeId1, "rel")

        graph.addEdge(fwd)
        val revEdge = graph.addEdge(rev)

        assertNotNull(revEdge)
        assertTrue(graph.containEdge(fwd))
        assertTrue(graph.containEdge(rev))
    }

    @Test
    fun `test addEdge_differentNodePairs_allowed`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val eid1 = EdgeID(nodeId1, nodeId2, "rel")
        val eid2 = EdgeID(nodeId1, nodeId3, "rel")
        val eid3 = EdgeID(nodeId2, nodeId3, "rel")

        graph.addEdge(eid1)
        graph.addEdge(eid2)
        graph.addEdge(eid3)

        assertTrue(graph.containEdge(eid1))
        assertTrue(graph.containEdge(eid2))
        assertTrue(graph.containEdge(eid3))
    }

    @Test
    fun `test addEdge_afterDelete_allowsReAddBetweenSameNodes`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val eid = EdgeID(nodeId1, nodeId2, "rel")
        graph.addEdge(eid)
        graph.delEdge(eid)

        val newEid = EdgeID(nodeId1, nodeId2, "newRel")
        val edge = graph.addEdge(newEid)

        assertNotNull(edge)
        assertTrue(graph.containEdge(newEid))
    }

    @Test
    fun `test addEdge_emptyType_succeeds`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val eid = EdgeID(nodeId1, nodeId2, "")

        val edge = graph.addEdge(eid)

        assertEquals("", edge.eType)
        assertTrue(graph.containEdge(eid))
    }

    // endregion

    // region Constraint boundary: uniqueness checks only cached edges

    @Test
    fun `test addEdge_storageOnlyEdgeSameDirection_doesNotBlockNew`() {
        val testGraph = TestSimpleGraph(storage)
        testGraph.addNode(nodeId1)
        testGraph.addNode(nodeId2)
        storage.addEdge(EdgeID(nodeId1, nodeId2, "old"))

        val newEid = EdgeID(nodeId1, nodeId2, "new")
        val edge = testGraph.addEdge(newEid)

        assertNotNull(edge)
    }

    @Test
    fun `test addEdge_writesToBothCacheAndStorage`() {
        val testGraph = TestSimpleGraph(storage)
        testGraph.addNode(nodeId1)
        testGraph.addNode(nodeId2)
        val eid = EdgeID(nodeId1, nodeId2, "rel")

        testGraph.addEdge(eid)

        assertTrue(testGraph.exposeEdgeIDs().contains(eid))
        assertTrue(storage.containsEdge(eid))
    }

    @Test
    fun `test addEdge_preExistingInStorage_reuseWithoutDuplicate`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val eid = EdgeID(nodeId1, nodeId2, "rel")
        storage.addEdge(eid)

        val edge = graph.addEdge(eid)

        assertEquals(eid, edge.id)
        assertTrue(storage.containsEdge(eid))
    }

    // endregion

    // region Edge retrieval

    @Test
    fun `test getEdge_existing_returnsEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val eid = EdgeID(nodeId1, nodeId2, "rel")
        graph.addEdge(eid)

        val edge = graph.getEdge(eid)

        assertNotNull(edge)
        assertEquals(eid, edge.id)
    }

    @Test
    fun `test getEdge_nonExistent_returnsNull`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)

        assertNull(graph.getEdge(EdgeID(nodeId1, nodeId2, "rel")))
    }

    @Test
    fun `test getEdge_inStorageButNotCache_returnsNull`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val eid = EdgeID(nodeId1, nodeId2, "rel")
        storage.addEdge(eid)

        assertNull(graph.getEdge(eid))
    }

    // endregion

    // region Performance

    @Test
    fun `test bulkNodeInsertion_completesInTime`() {
        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }
            }

        assertEquals(PERF_NODE_COUNT, graph.nodeIDs.size)
        println("SimpleGraph: $PERF_NODE_COUNT nodes inserted in $elapsed")
    }

    @Test
    fun `test bulkEdgeInsertion_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT - 1) { i ->
                    graph.addEdge(EdgeID(NodeID("n$i"), NodeID("n${i + 1}"), "e$i"))
                }
            }

        assertEquals(PERF_NODE_COUNT - 1, graph.edgeIDs.size)
        println("SimpleGraph: ${PERF_NODE_COUNT - 1} edges inserted in $elapsed")
    }

    @Test
    fun `test uniquenessCheck_overhead`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }
        val hubNode = NodeID("n0")
        repeat(PERF_NODE_COUNT - 1) { i ->
            graph.addEdge(EdgeID(hubNode, NodeID("n${i + 1}"), "e$i"))
        }

        val elapsed =
            kotlin.time.measureTime {
                repeat(100) {
                    assertFailsWith<EntityAlreadyExistException> {
                        graph.addEdge(EdgeID(hubNode, NodeID("n1"), "dup$it"))
                    }
                }
            }

        println("SimpleGraph: 100 uniqueness-check rejections (hub with ${PERF_NODE_COUNT - 1} outgoing) in $elapsed")
    }

    @Test
    fun `test descendantTraversal_completesInTime`() {
        repeat(PERF_CHAIN_LENGTH) { i -> graph.addNode(NodeID("c$i")) }
        repeat(PERF_CHAIN_LENGTH - 1) { i ->
            graph.addEdge(EdgeID(NodeID("c$i"), NodeID("c${i + 1}"), "next"))
        }

        val elapsed =
            kotlin.time.measureTime {
                val descendants = graph.getDescendants(NodeID("c0")).toList()
                assertEquals(PERF_CHAIN_LENGTH - 1, descendants.size)
            }

        println("SimpleGraph: descendant traversal of chain($PERF_CHAIN_LENGTH) in $elapsed")
    }

    // endregion

    companion object {
        private const val PERF_NODE_COUNT = 1000
        private const val PERF_CHAIN_LENGTH = 500
    }
}
