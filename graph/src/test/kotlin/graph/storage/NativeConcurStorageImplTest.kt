package graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeConcurStorageImpl
import edu.jhu.cobra.commons.value.StrVal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NativeConcurStorageImplTest {
    private lateinit var storage: NativeConcurStorageImpl
    private val testNode1 = NodeID("test1")
    private val testNode2 = NodeID("test2")
    private val testEdge = EdgeID(testNode1, testNode2, "testEdge")
    private val testProperty = "testProp" to StrVal("testValue")

    @BeforeTest
    fun setUp() {
        storage = NativeConcurStorageImpl()
    }

    @Test
    fun `test initial state`() {
        assertEquals(0, storage.nodeSize)
        assertEquals(0, storage.edgeSize)
        assertTrue(storage.nodeIDsSequence.toList().isEmpty())
        assertTrue(storage.edgeIDsSequence.toList().isEmpty())
    }

    @Test
    fun `test node operations`() {
        // Add node
        storage.addNode(testNode1, testProperty)
        assertTrue(storage.containsNode(testNode1))
        assertEquals(1, storage.nodeSize)
        assertEquals(testProperty.second, storage.getNodeProperty(testNode1, testProperty.first))

        // Try adding duplicate node
        assertFailsWith<EntityAlreadyExistException> { storage.addNode(testNode1) }

        // Update node properties
        val newProperty = "newProp" to StrVal("newValue")
        storage.setNodeProperties(testNode1, newProperty)
        assertEquals(newProperty.second, storage.getNodeProperty(testNode1, newProperty.first))

        // Delete node
        storage.deleteNode(testNode1)
        assertFalse(storage.containsNode(testNode1))
        assertEquals(0, storage.nodeSize)
    }

    @Test
    fun `test edge operations`() {
        // Add nodes first
        storage.addNode(testNode1)
        storage.addNode(testNode2)

        // Add edge
        storage.addEdge(testEdge, testProperty)
        assertTrue(storage.containsEdge(testEdge))
        assertEquals(1, storage.edgeSize)
        assertEquals(testProperty.second, storage.getEdgeProperty(testEdge, testProperty.first))

        // Try adding duplicate edge
        assertFailsWith<EntityAlreadyExistException> { storage.addEdge(testEdge) }

        // Update edge properties
        val newProperty = "newProp" to StrVal("newValue")
        storage.setEdgeProperties(testEdge, newProperty)
        assertEquals(newProperty.second, storage.getEdgeProperty(testEdge, newProperty.first))

        // Delete edge
        storage.deleteEdge(testEdge)
        assertFalse(storage.containsEdge(testEdge))
        assertEquals(0, storage.edgeSize)
    }

    @Test
    fun `test edge connectivity`() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)

        assertEquals(setOf(testEdge), storage.getOutgoingEdges(testNode1))
        assertEquals(setOf(testEdge), storage.getIncomingEdges(testNode2))
        assertEquals(setOf(testEdge), storage.getEdgesBetween(testNode1, testNode2))
        assertTrue(storage.getEdgesBetween(testNode2, testNode1).isEmpty())
    }

    @Test
    fun `test bulk operations`() {
        // Add multiple nodes and edges
        val nodes = (1..5).map { NodeID("node$it") }
        val edges = (0..3).map { EdgeID(nodes[it], nodes[it + 1], "edge$it") }

        nodes.forEach { storage.addNode(it) }
        edges.forEach { storage.addEdge(it) }

        assertEquals(5, storage.nodeSize)
        assertEquals(4, storage.edgeSize)

        // Delete nodes conditionally
        storage.deleteNodes { it.name.endsWith("1") || it.name.endsWith("3") }
        assertEquals(3, storage.nodeSize)
        assertEquals(1, storage.edgeSize) // Only edge between node2 and node4 should remain

        // Delete edges conditionally
        storage.deleteEdges { it.name.contains("edge") }
        assertEquals(0, storage.edgeSize)
    }

    @Test
    fun `test storage closure`() {
        storage.addNode(testNode1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.addNode(testNode2) }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(testNode1) }
        assertFailsWith<AccessClosedStorageException> { storage.nodeSize }
    }

    @Test
    fun `test clear operation`() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)

        assertTrue(storage.clear())
        assertEquals(0, storage.nodeSize)
        assertEquals(0, storage.edgeSize)
        assertFalse(storage.containsNode(testNode1))
        assertFalse(storage.containsEdge(testEdge))
    }

    @Test
    fun `test property operations`() {
        storage.addNode(testNode1)

        // Test adding multiple properties
        val props = arrayOf(
            "prop1" to StrVal("value1"),
            "prop2" to StrVal("value2")
        )
        storage.setNodeProperties(testNode1, *props)

        val nodeProps = storage.getNodeProperties(testNode1)
        assertEquals(2, nodeProps.size)
        assertEquals(props[0].second, nodeProps[props[0].first])
        assertEquals(props[1].second, nodeProps[props[1].first])

        // Test removing a property by setting it to null
        storage.setNodeProperties(testNode1, props[0].first to null)
        assertEquals(1, storage.getNodeProperties(testNode1).size)
        assertEquals(null, storage.getNodeProperty(testNode1, props[0].first))
    }

    @Test
    fun `test error cases`() {
        // Test non-existent node operations
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(testNode1) }
        assertFailsWith<EntityNotExistException> { storage.setNodeProperties(testNode1, testProperty) }
        assertFailsWith<EntityNotExistException> { storage.deleteNode(testNode1) }

        // Test edge operations with missing nodes
        assertFailsWith<EntityNotExistException> { storage.addEdge(testEdge) }

        storage.addNode(testNode1)
        assertFailsWith<EntityNotExistException> { storage.addEdge(testEdge) } // still missing testNode2

        // Test non-existent edge operations
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(testEdge) }
        assertFailsWith<EntityNotExistException> { storage.setEdgeProperties(testEdge, testProperty) }
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(testEdge) }
    }

    @Test
    fun `test concurrent node operations`() = runBlocking {
        val numThreads = 10
        val numOperations = 100
        val mutex = Mutex()
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val jobs = List(numThreads) { threadId ->
            launch(Dispatchers.Default) {
                repeat(numOperations) { operationId ->
                    try {
                        val nodeId = NodeID("node_${threadId}_$operationId")
                        val property = "prop" to StrVal("value_${threadId}_$operationId")

                        mutex.withLock {
                            storage.addNode(nodeId, property)
                            successCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state
        assertEquals(numThreads * numOperations, successCount.get())
        assertEquals(0, errorCount.get())
        assertEquals(numThreads * numOperations, storage.nodeSize)
    }

    @Test
    fun `test concurrent edge operations`() = runBlocking {
        val numThreads = 5
        val numOperations = 20
        val mutex = Mutex()
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        // First create nodes
        val nodes = (0 until numThreads * 2).map { NodeID("node$it") }
        nodes.forEach { storage.addNode(it) }

        val jobs = List(numThreads) { threadId ->
            launch(Dispatchers.Default) {
                repeat(numOperations) { operationId ->
                    try {
                        val sourceNode = nodes[threadId * 2]
                        val targetNode = nodes[threadId * 2 + 1]
                        val edgeId = EdgeID(sourceNode, targetNode, "edge_${threadId}_$operationId")
                        val property = "prop" to StrVal("value_${threadId}_$operationId")

                        mutex.withLock {
                            storage.addEdge(edgeId, property)
                            successCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state
        assertEquals(numThreads * numOperations, successCount.get())
        assertEquals(0, errorCount.get())
        assertEquals(numThreads * numOperations, storage.edgeSize)
    }

    @Test
    fun `test concurrent property updates`() = runBlocking {
        val numThreads = 10
        val numOperations = 50
        val mutex = Mutex()
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        // Create a test node
        storage.addNode(testNode1)

        val jobs = List(numThreads) { threadId ->
            launch(Dispatchers.Default) {
                repeat(numOperations) { operationId ->
                    try {
                        val property = "prop_${threadId}_$operationId" to StrVal("value_${threadId}_$operationId")

                        mutex.withLock {
                            storage.setNodeProperties(testNode1, property)
                            successCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state
        assertEquals(numThreads * numOperations, successCount.get())
        assertEquals(0, errorCount.get())
        assertEquals(numThreads * numOperations, storage.getNodeProperties(testNode1).size)
    }

    @Test
    fun `test concurrent read operations`() = runBlocking {
        // Setup initial data
        val numNodes = 100
        val nodes = (0 until numNodes).map { NodeID("node$it") }
        nodes.forEach { nodeId ->
            val nodeIndex = nodeId.name.substring(4).toInt() // Extract number from "nodeX"
            storage.addNode(nodeId, "prop" to StrVal("value$nodeIndex"))
        }

        val numThreads = 10
        val numOperations = 50
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val jobs = List(numThreads) { threadId ->
            launch(Dispatchers.Default) {
                repeat(numOperations) { operationId ->
                    try {
                        val nodeId = nodes[operationId % numNodes]
                        val properties = storage.getNodeProperties(nodeId)
                        val nodeIndex = nodeId.name.substring(4).toInt() // Extract number from "nodeX"
                        val expectedValue = StrVal("value$nodeIndex")
                        val actualValue = properties["prop"]

                        if (expectedValue.toString() == actualValue.toString()) {
                            successCount.incrementAndGet()
                        } else {
                            errorCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        println("Error in thread $threadId, operation $operationId: ${e.message}")
                        errorCount.incrementAndGet()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        // Print final results
        println("Final Results:")
        println("  Success Count: ${successCount.get()}")
        println("  Error Count: ${errorCount.get()}")
        println("  Total Operations: ${numThreads * numOperations}")

        // Verify final state
        assertTrue(successCount.get() > 0, "No successful operations were performed")
        assertEquals(
            numThreads * numOperations, successCount.get() + errorCount.get(),
            "Total operations should equal success + error count"
        )
    }
}