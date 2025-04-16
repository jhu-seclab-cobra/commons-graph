package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import kotlin.test.*

class AbcSimpleGraphTest {

    private lateinit var graph: TestSimpleGraph

    @BeforeTest
    fun setUp() {
        graph = TestSimpleGraph()
    }

    @AfterTest
    fun tearDown() {
        graph.clearCache()
    }

    @Test
    fun addNodeShouldAddNodeToGraph() {
        val nodeID = NodeID("A")
        val addedNode = graph.addNode(nodeID)

        assertEquals(nodeID, addedNode.id)
        assertTrue(graph.containNode(addedNode))
        assertTrue(graph.storage.containsNode(nodeID))
    }

    @Test
    fun addNodeShouldThrowExceptionIfNodeAlreadyExists() {
        val nodeID = NodeID("A")
        graph.addNode(nodeID)

        assertFailsWith<EntityAlreadyExistException> {
            graph.addNode(nodeID)
        }
    }

    @Test
    fun addEdgeShouldAddEdgeToGraph() {
        val nodeA = graph.addNode(NodeID("A"))
        val nodeB = graph.addNode(NodeID("B"))
        val edge = graph.addEdge(nodeA, nodeB, "type")

        assertTrue(graph.containEdge(edge))
        assertTrue(graph.storage.containsEdge(edge.id))
    }

    @Test
    fun addEdgeShouldThrowExceptionIfEdgeAlreadyExists() {
        val nodeA = graph.addNode(NodeID("A"))
        val nodeB = graph.addNode(NodeID("B"))
        graph.addEdge(nodeA, nodeB, "type")

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(nodeA, nodeB, "type")
        }
    }

    @Test
    fun wrapNodeShouldIncreaseRefCount() {
        val nodeID = NodeID("A")
        graph.storage.addNode(nodeID)
        val node = graph.wrapNode(graph.Node(nodeID))

        assertEquals(nodeID, node.id)
        assertTrue(graph.storage.containsNode(nodeID))
        assertEquals(2, graph.storage.getNodeProperty(nodeID, "ref_count")?.core)
    }

    @Test
    fun wrapNodeShouldThrowEntityNotExistExceptionIfNodeDoesNotExist() {
        val nodeID = NodeID("A")

        assertFailsWith<EntityNotExistException> {
            graph.wrapNode(graph.Node(nodeID))
        }
    }

    @Test
    fun getNodeShouldReturnNodeIfExists() {
        val nodeID = NodeID("A")
        graph.storage.addNode(nodeID)
        graph.addCache(id = nodeID)

        val node = graph.getNode(nodeID)
        assertNotNull(node)
        assertEquals(nodeID, node.id)
    }

    @Test
    fun getNodeShouldReturnNullIfNodeDoesNotExist() {
        val nodeID = NodeID("A")
        val node = graph.getNode(nodeID)
        assertNull(node)
    }

    @Test
    fun getEdgeShouldReturnEdgeIfExists() {
        val nodeA = NodeID("A")
        val nodeB = NodeID("B")
        graph.storage.addNode(nodeA)
        graph.storage.addNode(nodeB)
        val edgeID = EdgeID(nodeA, nodeB, "${graph.graphName}:type")
        graph.storage.addEdge(edgeID)
        graph.addCache(id = edgeID)

        val edge = graph.getEdge(graph.Node(nodeA), graph.Node(nodeB), "type")
        assertNotNull(edge)
        assertEquals(edgeID, edge.id)
    }

    @Test
    fun getEdgeShouldReturnNullIfEdgeDoesNotExist() {
        val nodeA = NodeID("A")
        val nodeB = NodeID("B")
        val edge = graph.getEdge(graph.Node(nodeA), graph.Node(nodeB), "type")
        assertNull(edge)
    }

    @Test
    fun delNodeShouldDeleteNodeAndEdges() {
        val nodeA = graph.addNode(NodeID("A"))
        val nodeB = graph.addNode(NodeID("B"))
        graph.addEdge(nodeA, nodeB, "type")

        graph.delNode(nodeA)

        assertFalse(graph.containNode(nodeA))
        assertFalse(graph.containCache(nodeA.id))
        assertFalse(graph.containCache(EdgeID(nodeA.id, nodeB.id, "type")))
    }

    @Test
    fun delEdgeShouldDeleteEdge() {
        val nodeA = graph.addNode(NodeID("A"))
        val nodeB = graph.addNode(NodeID("B"))
        val edge = graph.addEdge(nodeA, nodeB, "type")

        graph.delEdge(edge)

        assertFalse(graph.storage.containsEdge(edge.id))
        assertFalse(graph.containCache(edge.id))
    }

    @Test
    fun refreshCacheShouldClearAndReloadCache() {
        graph.storage.addNode(NodeID("A"))
        graph.storage.addNode(NodeID("B"))
        val edgeID = EdgeID(NodeID("A"), NodeID("B"), "${graph.graphName}:type")
        graph.storage.addEdge(edgeID)

        graph.refreshCache()

        assertTrue(graph.containCache(NodeID("A")))
        assertTrue(graph.containCache(NodeID("B")))
        assertTrue(graph.containCache(edgeID))
    }

    @Test
    fun clearCacheShouldClearAllNodeAndEdgeCaches() {
        val nodeA = graph.addNode(NodeID("A"))
        val edgeAB = graph.addEdge(nodeA, graph.addNode(NodeID("B")), "type")

        graph.clearCache()
        assertFalse(graph.containCache(nodeA.id))
        assertFalse(graph.containCache(edgeAB.id))
    }
}
