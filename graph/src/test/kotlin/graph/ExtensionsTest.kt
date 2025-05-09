package graph

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.AbcNode
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

class ExtensionsTest {
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

    private class TestGraph : AbcSimpleGraph<TestNode, TestEdge>() {
        override val storage = NativeStorageImpl()
        override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
        override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
    }

    private lateinit var graph: TestGraph
    private lateinit var node1: TestNode
    private lateinit var node2: TestNode
    private lateinit var edge: TestEdge

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
        node1 = graph.addNode(NodeID("node1"))
        node2 = graph.addNode(NodeID("node2"))
        edge = graph.addEdge(node1, node2, "testEdge")
    }

    @Test
    fun `test meta node operations`() {
        // 测试元数据节点ID
        assertEquals("Test__meta__", graph.META_NID.name)

        // 测试设置和获取元数据
        assertNull(graph.getMeta("testProp"))

        graph.setMeta("testProp", "testValue".strVal)
        assertEquals("testValue".strVal, graph.getMeta("testProp"))

        // 测试更新元数据
        graph.setMeta("testProp", "updatedValue".strVal)
        assertEquals("updatedValue".strVal, graph.getMeta("testProp"))

        // 测试删除元数据
        graph.setMeta("testProp", null)
        assertNull(graph.getMeta("testProp"))
    }

    @Test
    fun `test get operator for nodes and edges`() {
        // 测试节点获取
        assertEquals(node1, graph[node1.id])
        assertNull(graph[NodeID("nonexistent")])

        // 测试边获取
        assertEquals(edge, graph[edge.id])
        assertNull(graph[EdgeID(node1.id, node2.id, "nonexistent")])
    }

    @Test
    fun `test contains operator for IDs`() {
        // 测试节点ID
        assertTrue(node1.id in graph)
        assertFalse(NodeID("nonexistent") in graph)

        // 测试边ID
        assertTrue(edge.id in graph)
        assertFalse(EdgeID(node1.id, node2.id, "nonexistent") in graph)
    }

    @Test
    fun `test contains operator for entities`() {
        // 测试节点实体
        assertTrue(node1 in graph)
        assertFalse(TestNode(graph.storage, NodeID("nonexistent")) in graph)

        // 测试边实体
        assertTrue(edge in graph)
        assertFalse(TestEdge(graph.storage, EdgeID(node1.id, node2.id, "nonexistent")) in graph)
    }

    @Test
    fun `test wrap operator`() {
        val wrappedNode = graph wrap node1
        assertNotNull(wrappedNode)
        assertEquals(node1.id, wrappedNode.id)

        assertFailsWith<EntityNotExistException> {
            graph wrap TestNode(graph.storage, NodeID("nonexistent"))
        }
    }

    @Test
    fun `test group root operations`() {
        // 测试添加组根节点
        val root = graph.addGroupRoot("testGroup")
        assertEquals("Test@testGroup#0", root.id.name)
        assertTrue(root in graph)

        // 测试获取组根节点
        val retrievedRoot = graph.getGroupRoot("testGroup")
        assertNotNull(retrievedRoot)
        assertEquals(root.id, retrievedRoot.id)

        // 测试获取不存在的组根节点
        assertNull(graph.getGroupRoot("nonexistent"))
    }

    @Test
    fun `test group node operations`() {
        // 测试添加组节点（自动生成后缀）
        val node1 = graph.addGroupNode("testGroup")
        assertTrue(node1.id.name.startsWith("Test@testGroup#"))
        assertNotEquals("Test@testGroup#0", node1.id.name)

        // 测试添加组节点（指定后缀）
        val node2 = graph.addGroupNode("testGroup", "custom")
        assertEquals("Test@testGroup#custom", node2.id.name)

        // 测试添加同组节点
        val node3 = graph.addGroupNode(node1)
        assertTrue(node3.id.name.startsWith("Test@testGroup#"))
        assertNotEquals(node1.id.name, node3.id.name)

        // 测试获取组节点
        val retrievedNode = graph.getGroupNode("testGroup", "custom")
        assertNotNull(retrievedNode)
        assertEquals(node2.id, retrievedNode.id)
    }

    @Test
    fun `test group name operations`() {
        val root = graph.addGroupRoot("testGroup")
        val node = graph.addGroupNode("testGroup")

        // 测试获取组名
        assertEquals("testGroup", graph.getGroupName(root))
        assertEquals("testGroup", graph.getGroupName(node))

        // 测试获取组根节点（通过成员节点）
        val retrievedRoot = graph.getGroupRoot(node)
        assertNotNull(retrievedRoot)
        assertEquals(root.id, retrievedRoot.id)
    }

    @Test
    fun `test group node counter`() {
        // 测试节点计数器的自增
        val node1 = graph.addGroupNode("testGroup")
        val node2 = graph.addGroupNode("testGroup")
        val node3 = graph.addGroupNode("testGroup")

        // 验证节点ID是递增的
        val numbers = listOf(node1, node2, node3)
            .map { it.id.name.substringAfterLast("#").toInt() }
            .sorted()

        assertEquals(3, numbers.size)
        assertEquals(1, numbers[0])
        assertEquals(2, numbers[1])
        assertEquals(3, numbers[2])

        // 验证计数器存储在元数据中
        val counter = graph.getMeta("testGroup_cnt") as? NumVal
        assertNotNull(counter)
        assertEquals(3, counter.toInt())
    }

    @Test
    fun `test group node uniqueness`() {
        // 测试自动生成的节点ID不重复
        val nodes = List(10) { graph.addGroupNode("testGroup") }
        val uniqueIds = nodes.map { it.id.name }.toSet()
        assertEquals(nodes.size, uniqueIds.size)

        // 测试指定后缀的节点不能重复
        graph.addGroupNode("testGroup", "custom")
        assertFailsWith<EntityAlreadyExistException> {
            graph.addGroupNode("testGroup", "custom")
        }
    }
}