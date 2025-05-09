package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.AbcNode
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

class AbcMultipleGraphTest {
    // 测试用的简单实现类
    private class TestNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
        enum class Type {
            TEST
        }

        override val type: AbcNode.Type
            get() = object : AbcNode.Type {
                override val name: String get() = Type.TEST.name
            }
    }

    private class TestEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
        enum class Type {
            TEST
        }

        override val type: AbcEdge.Type
            get() = object : AbcEdge.Type {
                override val name: String get() = Type.TEST.name
            }
    }

    private class TestGraph : AbcMultiGraph<TestNode, TestEdge>() {
        override val storage = NativeStorageImpl()
        override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
        override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
    }

    private lateinit var graph: TestGraph
    private lateinit var node1: TestNode
    private lateinit var node2: TestNode

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
        node1 = graph.addNode(NodeID("node1"))
        node2 = graph.addNode(NodeID("node2"))
    }

    @Test
    fun `test getStorage`() {
        assertNotNull(graph.storage)
        assertIs<NativeStorageImpl>(graph.storage)
    }

    @Test
    fun `test entity size`() {
        assertEquals(2, graph.entitySize) // 初始状态只有2个节点

        // 添加边后检查
        graph.addEdge(node1, node2, "edge1")
        assertEquals(3, graph.entitySize)
    }

    @Test
    fun `test getGraphName`() {
        assertEquals("Test", graph.graphName)
    }

    @Test
    fun `test containNode`() {
        assertTrue(graph.containNode(node1))
        assertFalse(graph.containNode(TestNode(graph.storage, NodeID("nonexistent"))))
    }

    @Test
    fun `test containEdge`() {
        val edge = graph.addEdge(node1, node2, "testEdge")
        assertTrue(graph.containEdge(edge))
        assertFalse(graph.containEdge(TestEdge(graph.storage, EdgeID(node1.id, node2.id, "nonexistent"))))
    }

    @Test
    fun `test addNode`() {
        val nodeId = NodeID("newNode")
        val node = graph.addNode(nodeId)
        assertNotNull(node)
        assertTrue(graph.containNode(node))
        assertEquals(nodeId, node.id)

        // 测试添加重复节点
        assertFailsWith<EntityAlreadyExistException> {
            graph.addNode(nodeId)
        }
    }

    @Test
    fun `test wrapNode`() {
        val nodeId = NodeID("testWrap")
        graph.storage.addNode(nodeId)

        val node = TestNode(graph.storage, nodeId)
        val wrapped = graph.wrapNode(node)
        assertNotNull(wrapped)
        assertEquals(nodeId, wrapped.id)

        // 测试包装不存在的节点
        assertFailsWith<EntityNotExistException> {
            graph.wrapNode(TestNode(graph.storage, NodeID("nonexistent")))
        }
    }

    @Test
    fun `test getNode`() {
        val node = graph.getNode(node1.id)
        assertNotNull(node)
        assertEquals(node1.id, node.id)

        assertNull(graph.getNode(NodeID("nonexistent")))
    }

    @Test
    fun `test getEdge and getEdges`() {
        // 添加多条边
        val edge1 = graph.addEdge(node1, node2, "type1")
        val edge2 = graph.addEdge(node1, node2, "type2")

        // 测试通过 EdgeID 获取
        val retrievedEdge = graph.getEdge(edge1.id)
        assertNotNull(retrievedEdge)
        assertEquals(edge1.id, retrievedEdge.id)

        // 测试通过节点和类型获取
        val edgeByType = graph.getEdge(node1, node2, "type1")
        assertNotNull(edgeByType)
        assertEquals(edge1.id, edgeByType.id)

        // 测试获取所有边
        val allEdges = graph.getEdges(node1, node2).toList()
        assertEquals(2, allEdges.size)
        assertTrue(allEdges.any { it.id == edge1.id })
        assertTrue(allEdges.any { it.id == edge2.id })
    }

    @Test
    fun `test getAllNodes`() {
        val nodes = graph.getAllNodes().toList()
        assertEquals(2, nodes.size)
        assertTrue(nodes.any { it.id == node1.id })
        assertTrue(nodes.any { it.id == node2.id })

        // 测试带条件的查询
        val filteredNodes = graph.getAllNodes { it.id == node1.id }.toList()
        assertEquals(1, filteredNodes.size)
        assertEquals(node1.id, filteredNodes[0].id)
    }

    @Test
    fun `test getAllEdges`() {
        val edge1 = graph.addEdge(node1, node2, "type1")
        val edge2 = graph.addEdge(node1, node2, "type2")

        val edges = graph.getAllEdges().toList()
        assertEquals(2, edges.size)
        assertTrue(edges.any { it.id == edge1.id })
        assertTrue(edges.any { it.id == edge2.id })

        // 测试带条件的查询
        val filteredEdges = graph.getAllEdges { it.id.eType.endsWith("type1") }.toList()
        assertEquals(1, filteredEdges.size)
        assertEquals(edge1.id, filteredEdges[0].id)
    }

    @Test
    fun `test getOutgoingEdges`() {
        val edge1 = graph.addEdge(node1, node2, "type1")
        val edge2 = graph.addEdge(node1, node2, "type2")

        val outEdges = graph.getOutgoingEdges(node1).toList()
        assertEquals(2, outEdges.size)
        assertTrue(outEdges.any { it.id == edge1.id })
        assertTrue(outEdges.any { it.id == edge2.id })

        val emptyOutEdges = graph.getOutgoingEdges(node2).toList()
        assertTrue(emptyOutEdges.isEmpty())
    }

    @Test
    fun `test getIncomingEdges`() {
        val edge1 = graph.addEdge(node1, node2, "type1")
        val edge2 = graph.addEdge(node1, node2, "type2")

        val inEdges = graph.getIncomingEdges(node2).toList()
        assertEquals(2, inEdges.size)
        assertTrue(inEdges.any { it.id == edge1.id })
        assertTrue(inEdges.any { it.id == edge2.id })

        val emptyInEdges = graph.getIncomingEdges(node1).toList()
        assertTrue(emptyInEdges.isEmpty())
    }

    @Test
    fun `test getParents`() {
        graph.addEdge(node1, node2, "type1")
        graph.addEdge(node1, node2, "type2")

        val parents = graph.getParents(node2).toList()
        assertEquals(2, parents.size)
        assertEquals(node1.id, parents[0].id)

        // 测试带条件的查询
        val filteredParents = graph.getParents(node2) { it.id.eType.endsWith("type1") }.toList()
        assertEquals(1, filteredParents.size)
        assertEquals(node1.id, filteredParents[0].id)
    }

    @Test
    fun `test getChildren`() {
        graph.addEdge(node1, node2, "type1")
        graph.addEdge(node1, node2, "type2")

        val children = graph.getChildren(node1).toList()
        assertEquals(2, children.size)
        assertEquals(node2.id, children[0].id)

        // 测试带条件的查询
        val filteredChildren = graph.getChildren(node1) { it.id.eType.endsWith("type1") }.toList()
        assertEquals(1, filteredChildren.size)
        assertEquals(node2.id, filteredChildren[0].id)
    }

    @Test
    fun `test getAncestors`() {
        // 创建一个更复杂的图结构
        val node3 = graph.addNode(NodeID("node3"))
        graph.addEdge(node1, node2, "type1")
        graph.addEdge(node1, node2, "type2")
        graph.addEdge(node2, node3, "type3")

        val ancestors = graph.getAncestors(node3).toList()
        assertEquals(3, ancestors.size)
        assertTrue(ancestors.any { it.id == node1.id })
        assertTrue(ancestors.any { it.id == node2.id })

        // 测试带条件的查询
        val filteredAncestors = graph.getAncestors(node3) { it.id.eType.endsWith("type3") }.toList()
        assertEquals(1, filteredAncestors.size)
        assertEquals(node2.id, filteredAncestors[0].id)
    }

    @Test
    fun `test getDescendants`() {
        // 创建一个更复杂的图结构
        val node3 = graph.addNode(NodeID("node3"))
        graph.addEdge(node1, node2, "type1")
        graph.addEdge(node1, node2, "type2")
        graph.addEdge(node2, node3, "type3")

        val descendants = graph.getDescendants(node1).toList()
        assertEquals(3, descendants.size)
        assertTrue(descendants.any { it.id == node2.id })
        assertTrue(descendants.any { it.id == node3.id })

        // 测试带条件的查询
        val filteredDescendants = graph.getDescendants(node1) { it.id.eType.endsWith("type1") }.toList()
        assertEquals(1, filteredDescendants.size)
        assertEquals(node2.id, filteredDescendants[0].id)
    }

    @Test
    fun `test delNode`() {
        val edge1 = graph.addEdge(node1, node2, "type1")
        val edge2 = graph.addEdge(node1, node2, "type2")

        graph.delNode(node1)
        assertFalse(graph.containNode(node1))
        assertFalse(graph.containEdge(edge1))
        assertFalse(graph.containEdge(edge2))
    }

    @Test
    fun `test delEdge`() {
        val edge1 = graph.addEdge(node1, node2, "type1")
        val edge2 = graph.addEdge(node1, node2, "type2")

        graph.delEdge(edge1)
        assertFalse(graph.containEdge(edge1))
        assertTrue(graph.containEdge(edge2))
        assertTrue(graph.containNode(node1))
        assertTrue(graph.containNode(node2))
    }

    @Test
    fun `test multiple edges between same nodes`() {
        // 测试可以添加多条边
        val edge1 = graph.addEdge(node1, node2, "type1")
        val edge2 = graph.addEdge(node1, node2, "type2")

        assertNotNull(edge1)
        assertNotNull(edge2)
        assertNotEquals(edge1.id, edge2.id)

        // 测试相同类型的边会抛出异常
        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(node1, node2, "type1")
        }
    }

    @Test
    fun `test edge type prefixing`() {
        val edge = graph.addEdge(node1, node2, "testType")
        assertEquals("Test:testType", edge.id.eType)
    }

    @Test
    fun `test auto generated edge type`() {
        val edge = graph.addEdge(node1, node2)
        assertTrue(edge.id.eType.startsWith("Test:"))
        assertTrue(edge.id.eType.length > "Test:".length)
    }
}