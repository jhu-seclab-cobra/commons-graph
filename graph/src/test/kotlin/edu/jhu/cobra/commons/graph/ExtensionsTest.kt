package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.createTestMultipleGraph
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeId1
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId1
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId2
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.mapVal
import kotlin.test.*

class ExtensionsTest {

    private lateinit var graph: AbcMultipleGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = createTestMultipleGraph(storage)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // region contains operator

    @Test
    fun `test contains_nodeID_existing_returnsTrue`() {
        graph.addNode(nodeId1)

        assertTrue(nodeId1 in graph)
    }

    @Test
    fun `test contains_nodeID_nonExistent_returnsFalse`() {
        assertFalse(nodeId1 in graph)
    }

    @Test
    fun `test contains_edgeID_existing_returnsTrue`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        assertTrue(edgeId1 in graph)
    }

    @Test
    fun `test contains_edgeID_nonExistent_returnsFalse`() {
        assertFalse(edgeId1 in graph)
    }

    @Test
    fun `test contains_nodeEntity_existing_returnsTrue`() {
        val node = graph.addNode(nodeId1)

        assertTrue(node in graph)
    }

    @Test
    fun `test contains_edgeEntity_existing_returnsTrue`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(edgeId1)

        assertTrue(edge in graph)
    }

    // endregion

    // region get operator

    @Test
    fun `test get_nodeID_existing_returnsNode`() {
        graph.addNode(nodeId1)

        val node = graph[nodeId1]

        assertNotNull(node)
        assertEquals(nodeId1, node.id)
    }

    @Test
    fun `test get_nodeID_nonExistent_returnsNull`() {
        assertNull(graph[nodeId1])
    }

    @Test
    fun `test get_edgeID_existing_returnsEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        val edge = graph[edgeId1]

        assertNotNull(edge)
        assertEquals(edgeId1, edge.id)
    }

    @Test
    fun `test get_edgeID_nonExistent_returnsNull`() {
        assertNull(graph[edgeId1])
    }

    // endregion

    // region toEntityID

    @Test
    fun `test toEntityID_strVal_returnsNodeID`() {
        val strVal = StrVal("node1")

        val id: NodeID = strVal.toEntityID()

        assertEquals(NodeID("node1"), id)
    }

    @Test
    fun `test toEntityID_listVal_returnsEdgeID`() {
        val eid = EdgeID(nodeId1, nodeId2, "rel")

        val id: EdgeID = eid.serialize.toEntityID()

        assertEquals(eid, id)
    }

    @Test
    fun `test toEntityID_unsupportedType_throwsIllegalArgument`() {
        val unsupported = emptyMap<String, edu.jhu.cobra.commons.value.IValue>().mapVal

        assertFailsWith<IllegalArgumentException> { unsupported.toEntityID<NodeID>() }
    }

    // endregion
}
