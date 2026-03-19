package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphTestUtils.createTestSimpleGraph
import edu.jhu.cobra.commons.graph.poset.Label
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
        assertTrue(graph.containEdge(edge.srcNid, edge.dstNid, edge.eTag))
    }

    @Test
    fun `test addEdge_duplicateSameNodePairSameTag_throwsEntityAlreadyExist`() {
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
        assertTrue(graph.containEdge(fwdEdge.srcNid, fwdEdge.dstNid, fwdEdge.eTag))
        assertTrue(graph.containEdge(revEdge.srcNid, revEdge.dstNid, revEdge.eTag))
    }

    @Test
    fun `test addEdge_differentNodePairs_allowed`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)

        val e1 = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")
        val e2 = graph.addEdge(NODE_ID_1, NODE_ID_3, "rel")
        val e3 = graph.addEdge(NODE_ID_2, NODE_ID_3, "rel")

        assertTrue(graph.containEdge(e1.srcNid, e1.dstNid, e1.eTag))
        assertTrue(graph.containEdge(e2.srcNid, e2.dstNid, e2.eTag))
        assertTrue(graph.containEdge(e3.srcNid, e3.dstNid, e3.eTag))
    }

    @Test
    fun `test addEdge_afterDelete_allowsReAddBetweenSameNodes`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")
        graph.delEdge(edge.srcNid, edge.dstNid, edge.eTag)

        val newEdge = graph.addEdge(NODE_ID_1, NODE_ID_2, "newRel")

        assertNotNull(newEdge)
        assertTrue(graph.containEdge(newEdge.srcNid, newEdge.dstNid, newEdge.eTag))
    }

    @Test
    fun `test addEdge_emptyType_succeeds`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "")

        assertEquals("", edge.eTag)
        assertTrue(graph.containEdge(edge.srcNid, edge.dstNid, edge.eTag))
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

        assertTrue(storage.containsEdge(edge.edgeId))
    }

    @Test
    fun `test addEdge_duplicate_throwsEntityAlreadyExist`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        assertFailsWith<EntityAlreadyExistException> { graph.addEdge(NODE_ID_1, NODE_ID_2, "rel") }
    }

    // endregion

    // region Label-aware addEdge

    @Test
    fun `test addEdge_withLabel_newEdge_succeeds`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val label = Label("v1")

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel", label)

        assertNotNull(edge)
        assertTrue(edge.labels.contains(label))
    }

    @Test
    fun `test addEdge_withLabel_existingSameTag_addsLabel`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val label1 = Label("v1")
        val label2 = Label("v2")
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel", label1)

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel", label2)

        assertTrue(edge.labels.contains(label1))
        assertTrue(edge.labels.contains(label2))
    }

    @Test
    fun `test addEdge_withLabel_existingDifferentType_throwsEntityAlreadyExist`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "typeA", Label("v1"))

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, "typeB", Label("v2"))
        }
    }

    // endregion

    // region Edge retrieval

    @Test
    fun `test getEdge_existing_returnsEdge`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        val retrieved = graph.getEdge(edge.srcNid, edge.dstNid, edge.eTag)

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

        assertNotNull(graph.getEdge(edge.srcNid, edge.dstNid, edge.eTag))
    }

    // endregion
}
