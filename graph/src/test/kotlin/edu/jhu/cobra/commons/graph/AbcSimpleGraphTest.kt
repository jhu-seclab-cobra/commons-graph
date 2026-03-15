package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphTestUtils.createTestSimpleGraph
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
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        assertNotNull(edge)
        assertTrue(graph.containEdge(edge.srcNid, edge.dstNid, edge.eType))
    }

    @Test
    fun `test addEdge_duplicateSameNodePairSameType_throwsEntityAlreadyExist`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        assertFailsWith<EntityAlreadyExistException> { graph.addEdge(NODE_ID_1, NODE_ID_2, "rel") }
    }

    @Test
    fun `test addEdge_sameNodePairDifferentType_throwsEntityAlreadyExist`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "typeA")

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, "typeB")
        }
    }

    @Test
    fun `test addEdge_reverseDirection_allowed`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        val fwdEdge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")
        val revEdge = graph.addEdge(NODE_ID_2, NODE_ID_1, "rel")

        assertNotNull(revEdge)
        assertTrue(graph.containEdge(fwdEdge.srcNid, fwdEdge.dstNid, fwdEdge.eType))
        assertTrue(graph.containEdge(revEdge.srcNid, revEdge.dstNid, revEdge.eType))
    }

    @Test
    fun `test addEdge_differentNodePairs_allowed`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)

        val e1 = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")
        val e2 = graph.addEdge(NODE_ID_1, NODE_ID_3, "rel")
        val e3 = graph.addEdge(NODE_ID_2, NODE_ID_3, "rel")

        assertTrue(graph.containEdge(e1.srcNid, e1.dstNid, e1.eType))
        assertTrue(graph.containEdge(e2.srcNid, e2.dstNid, e2.eType))
        assertTrue(graph.containEdge(e3.srcNid, e3.dstNid, e3.eType))
    }

    @Test
    fun `test addEdge_afterDelete_allowsReAddBetweenSameNodes`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")
        graph.delEdge(edge.srcNid, edge.dstNid, edge.eType)

        val newEdge = graph.addEdge(NODE_ID_1, NODE_ID_2, "newRel")

        assertNotNull(newEdge)
        assertTrue(graph.containEdge(newEdge.srcNid, newEdge.dstNid, newEdge.eType))
    }

    @Test
    fun `test addEdge_emptyType_succeeds`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "")

        assertEquals("", edge.eType)
        assertTrue(graph.containEdge(edge.srcNid, edge.dstNid, edge.eType))
    }

    // endregion

    // region Storage delegation

    @Test
    fun `test addEdge_existingEdgeSameDirection_blocksNew`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "old")

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, "new")
        }
    }

    @Test
    fun `test addEdge_writesToStorage`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        assertTrue(storage.containsEdge(edge.internalId))
    }

    @Test
    fun `test addEdge_duplicate_throwsEntityAlreadyExist`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        assertFailsWith<EntityAlreadyExistException> { graph.addEdge(NODE_ID_1, NODE_ID_2, "rel") }
    }

    // endregion

    // region Edge retrieval

    @Test
    fun `test getEdge_existing_returnsEdge`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        val retrieved = graph.getEdge(edge.srcNid, edge.dstNid, edge.eType)

        assertNotNull(retrieved)
        assertEquals(edge.id, retrieved.id)
    }

    @Test
    fun `test getEdge_nonExistent_returnsNull`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        assertNull(graph.getEdge(NODE_ID_1, NODE_ID_2, "nonexistent"))
    }

    @Test
    fun `test getEdge_afterAdd_returnsEdge`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        assertNotNull(graph.getEdge(edge.srcNid, edge.dstNid, edge.eType))
    }

    // endregion

    // region Performance

    @Test
    fun `test bulkNodeInsertion_completesInTime`() {
        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }
            }

        assertEquals(PERF_NODE_COUNT, graph.nodeIDs.size)
        println("SimpleGraph: $PERF_NODE_COUNT nodes inserted in $elapsed")
    }

    @Test
    fun `test bulkEdgeInsertion_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT - 1) { i ->
                    graph.addEdge("n$i", "n${i + 1}", "e$i")
                }
            }

        assertEquals(PERF_NODE_COUNT - 1, graph.getAllEdges().count())
        println("SimpleGraph: ${PERF_NODE_COUNT - 1} edges inserted in $elapsed")
    }

    @Test
    fun `test uniquenessCheck_overhead`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }
        val hubNode: NodeID = "n0"
        repeat(PERF_NODE_COUNT - 1) { i ->
            graph.addEdge(hubNode, "n${i + 1}", "e$i")
        }

        val elapsed =
            kotlin.time.measureTime {
                repeat(100) {
                    assertFailsWith<EntityAlreadyExistException> {
                        graph.addEdge(hubNode, "n1", "dup$it")
                    }
                }
            }

        println("SimpleGraph: 100 uniqueness-check rejections (hub with ${PERF_NODE_COUNT - 1} outgoing) in $elapsed")
    }

    @Test
    fun `test descendantTraversal_completesInTime`() {
        repeat(PERF_CHAIN_LENGTH) { i -> graph.addNode("c$i") }
        repeat(PERF_CHAIN_LENGTH - 1) { i ->
            graph.addEdge("c$i", "c${i + 1}", "next")
        }

        val elapsed =
            kotlin.time.measureTime {
                val descendants = graph.getDescendants("c0").toList()
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
