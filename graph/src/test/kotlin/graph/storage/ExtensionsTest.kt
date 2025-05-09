package graph.storage

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.*
import edu.jhu.cobra.commons.value.StrVal
import kotlin.test.*

class ExtensionsTest {
    private lateinit var storage: NativeStorageImpl
    private val testNode = NodeID("test")
    private val testNode2 = NodeID("test2")
    private val testEdge = EdgeID(testNode, testNode2, "testEdge")
    private val testProp = "testProp" to StrVal("testValue")
    private val testProp2 = "testProp2" to StrVal("testValue2")

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
    }

    @Test
    fun `test contains operator for nodes and edges`() {
        assertFalse(testNode in storage)
        assertFalse(testEdge in storage)

        storage.addNode(testNode)
        assertTrue(testNode in storage)
        assertFalse(testEdge in storage)

        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        assertTrue(testEdge in storage)
    }

    @Test
    fun `test contains operator for metadata`() {
        assertFalse("metaProp" in storage)

        storage.setMeta("metaProp" to StrVal("metaValue"))
        assertTrue("metaProp" in storage)
    }

    @Test
    fun `test set operator for nodes and edges`() {
        // Test node creation and update
        storage[testNode] = mapOf(testProp)
        assertTrue(testNode in storage)
        assertEquals(testProp.second, storage.getNodeProperty(testNode, testProp.first))

        // Test node property update
        storage[testNode] = mapOf(testProp.first to StrVal("updatedValue"))
        assertEquals(StrVal("updatedValue"), storage.getNodeProperty(testNode, testProp.first))

        // Test edge creation and update
        storage.addNode(testNode2)
        storage[testEdge] = mapOf(testProp)
        assertTrue(testEdge in storage)
        assertEquals(testProp.second, storage.getEdgeProperty(testEdge, testProp.first))
    }

    @Test
    fun `test set operator for metadata`() {
        storage["metaProp"] = StrVal("metaValue")
        assertEquals(StrVal("metaValue"), storage.getMeta("metaProp"))
    }

    @Test
    fun `test get operator for nodes and edges`() {
        storage.addNode(testNode, testProp, testProp2)
        val nodeProps = storage[testNode]
        assertEquals(2, nodeProps.size)
        assertEquals(testProp.second, nodeProps[testProp.first])
        assertEquals(testProp2.second, nodeProps[testProp2.first])

        storage.addNode(testNode2)
        storage.addEdge(testEdge, testProp)
        val edgeProps = storage[testEdge]
        assertEquals(1, edgeProps.size)
        assertEquals(testProp.second, edgeProps[testProp.first])
    }

    @Test
    fun `test get operator for metadata`() {
        assertNull(storage["metaProp"])

        storage.setMeta("metaProp" to StrVal("metaValue"))
        assertEquals(StrVal("metaValue"), storage["metaProp"])
    }

    @Test
    fun `test get operator for specific properties`() {
        storage.addNode(testNode, testProp)
        assertEquals(testProp.second, storage[testNode to testProp.first])

        storage.addNode(testNode2)
        storage.addEdge(testEdge, testProp)
        assertEquals(testProp.second, storage[testEdge to testProp.first])
    }

    @Test
    fun `test set operator for specific properties`() {
        storage.addNode(testNode)
        storage[testNode to "prop"] = StrVal("value")
        assertEquals(StrVal("value"), storage.getNodeProperty(testNode, "prop"))

        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        storage[testEdge to "prop"] = StrVal("value")
        assertEquals(StrVal("value"), storage.getEdgeProperty(testEdge, "prop"))

        // Test property deletion
        storage[testNode to "prop"] = null
        assertNull(storage.getNodeProperty(testNode, "prop"))
    }

    @Test
    fun `test setMeta function`() {
        val prop1 = "prop1" to StrVal("value1")
        val prop2 = "prop2" to StrVal("value2")

        storage.setMeta(prop1, prop2)
        assertEquals(StrVal("value1"), storage.getMeta("prop1"))
        assertEquals(StrVal("value2"), storage.getMeta("prop2"))

        // Test overwriting metadata
        storage.setMeta("prop1" to StrVal("newValue"))
        assertEquals(StrVal("newValue"), storage.getMeta("prop1"))
    }

    @Test
    fun `test toTypeArray extension function`() {
        val map = mapOf(
            "key1" to "value1",
            "key2" to "value2"
        )
        val array = map.toTypeArray()

        assertEquals(2, array.size)
        assertTrue(array.contains("key1" to "value1"))
        assertTrue(array.contains("key2" to "value2"))
    }
}
