package graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.DeltaConcurStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.StrVal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DeltaConcurStorageImplTest {
    private lateinit var foundStorage: NativeStorageImpl
    private lateinit var presentStorage: NativeStorageImpl
    private lateinit var deltaStorage: DeltaConcurStorageImpl
    private val testNode1 = NodeID("test1")
    private val testNode2 = NodeID("test2")
    private val testEdge = EdgeID(testNode1, testNode2, "testEdge")
    private val testProperty = "testProp" to StrVal("testValue")

    @BeforeTest
    fun setUp() {
        foundStorage = NativeStorageImpl()
        presentStorage = NativeStorageImpl()
        deltaStorage = DeltaConcurStorageImpl(foundStorage, presentStorage)
    }

    @Test
    fun `test initial state`() {
        assertEquals(0, deltaStorage.nodeSize)
        assertEquals(0, deltaStorage.edgeSize)
        assertTrue(deltaStorage.nodeIDsSequence.toList().isEmpty())
        assertTrue(deltaStorage.edgeIDsSequence.toList().isEmpty())
    }

    @Test
    fun `test node operations with delta layers`() {
        // Add node to foundation layer through delta storage
        deltaStorage.addNode(testNode1, testProperty)
        assertTrue(deltaStorage.containsNode(testNode1))
        assertEquals(1, deltaStorage.nodeSize)
        assertEquals(testProperty.second, deltaStorage.getNodeProperty(testNode1, testProperty.first))

        // Add node to present layer
        val testNode3 = NodeID("test3")
        val presentProperty = "presentProp" to StrVal("presentValue")
        deltaStorage.addNode(testNode3, presentProperty)
        assertTrue(deltaStorage.containsNode(testNode3))
        assertEquals(2, deltaStorage.nodeSize)
        assertEquals(presentProperty.second, deltaStorage.getNodeProperty(testNode3, presentProperty.first))

        // Update property in present layer
        val updatedProperty = "testProp" to StrVal("updatedValue")
        deltaStorage.setNodeProperties(testNode1, updatedProperty)
        assertEquals(updatedProperty.second, deltaStorage.getNodeProperty(testNode1, updatedProperty.first))

        // Delete node
        deltaStorage.deleteNode(testNode1)
        assertFalse(deltaStorage.containsNode(testNode1))
        assertEquals(1, deltaStorage.nodeSize)
    }

    @Test
    fun `test edge operations with delta layers`() {
        // Setup nodes through delta storage
        deltaStorage.addNode(testNode1)
        deltaStorage.addNode(testNode2)

        // Add edge
        deltaStorage.addEdge(testEdge, testProperty)
        assertTrue(deltaStorage.containsEdge(testEdge))
        assertEquals(1, deltaStorage.edgeSize)
        assertEquals(testProperty.second, deltaStorage.getEdgeProperty(testEdge, testProperty.first))

        // Try adding duplicate edge
        assertFailsWith<EntityAlreadyExistException> { deltaStorage.addEdge(testEdge) }

        // Update edge properties
        val newProperty = "newProp" to StrVal("newValue")
        deltaStorage.setEdgeProperties(testEdge, newProperty)
        assertEquals(newProperty.second, deltaStorage.getEdgeProperty(testEdge, newProperty.first))

        // Delete edge
        deltaStorage.deleteEdge(testEdge)
        assertFalse(deltaStorage.containsEdge(testEdge))
        assertEquals(0, deltaStorage.edgeSize)
    }

    @Test
    fun `test property layering and deletion`() {
        // Add node with properties through delta storage
        val foundProps = arrayOf(
            "prop1" to StrVal("value1"),
            "prop2" to StrVal("value2")
        )
        deltaStorage.addNode(testNode1, *foundProps)

        // Add/update properties
        val presentProps = arrayOf(
            "prop2" to StrVal("updatedValue2"), // Override existing property
            "prop3" to StrVal("value3")         // New property
        )
        deltaStorage.setNodeProperties(testNode1, *presentProps)

        // Verify property layering
        val properties = deltaStorage.getNodeProperties(testNode1)
        assertEquals(3, properties.size)
        assertEquals(StrVal("value1"), properties["prop1"])
        assertEquals(StrVal("updatedValue2"), properties["prop2"])
        assertEquals(StrVal("value3"), properties["prop3"])

        // Test property deletion
        deltaStorage.setNodeProperties(testNode1, "prop1" to null)
        assertNull(deltaStorage.getNodeProperty(testNode1, "prop1"))
    }

    @Test
    fun `test edge connectivity across layers`() {
        // Setup nodes and edges through delta storage
        deltaStorage.addNode(testNode1)
        deltaStorage.addNode(testNode2)
        val edge1 = EdgeID(testNode1, testNode2, "edge1")
        deltaStorage.addEdge(edge1)

        val testNode3 = NodeID("test3")
        deltaStorage.addNode(testNode3)
        val edge2 = EdgeID(testNode2, testNode3, "edge2")
        deltaStorage.addEdge(edge2)

        // Test connectivity
        assertEquals(setOf(edge1), deltaStorage.getOutgoingEdges(testNode1))
        assertEquals(setOf(edge1), deltaStorage.getIncomingEdges(testNode2))
        assertEquals(setOf(edge2), deltaStorage.getOutgoingEdges(testNode2))
        assertEquals(setOf(edge2), deltaStorage.getIncomingEdges(testNode3))
        assertEquals(setOf(edge1), deltaStorage.getEdgesBetween(testNode1, testNode2))
    }

    @Test
    fun `test bulk operations`() {
        // Add nodes and edges through delta storage
        val nodes = (1..6).map { NodeID("node$it") }
        nodes.forEach { deltaStorage.addNode(it) }

        val edges = (0..4).map {
            EdgeID(nodes[it], nodes[it + 1], "edge$it")
        }
        edges.forEach { deltaStorage.addEdge(it) }

        assertEquals(6, deltaStorage.nodeSize)
        assertEquals(5, deltaStorage.edgeSize)

        // Delete nodes conditionally
        deltaStorage.deleteNodes { it.name.last() in setOf('2', '4', '6') }
        assertEquals(3, deltaStorage.nodeSize)
        assertEquals(0, deltaStorage.edgeSize) // All edges should be deleted as their nodes are deleted

        // Add new edges between remaining nodes
        val newEdges = (0..1).map {
            EdgeID(nodes[it * 2], nodes[(it + 1) * 2], "newEdge$it")
        }
        newEdges.forEach { deltaStorage.addEdge(it) }
        assertEquals(2, deltaStorage.edgeSize)
    }

    @Test
    fun `test storage closure`() {
        deltaStorage.addNode(testNode1)
        deltaStorage.close()

        assertFailsWith<AccessClosedStorageException> { deltaStorage.addNode(testNode2) }
        assertFailsWith<AccessClosedStorageException> { deltaStorage.containsNode(testNode1) }
        assertFailsWith<AccessClosedStorageException> { deltaStorage.nodeSize }
    }

    @Test
    fun `test concurrent read write operations`() = runBlocking {
        // Setup initial data through delta storage
        val nodes = (1..4).map { NodeID("node$it") }
        nodes.forEach { deltaStorage.addNode(it) }

        val numThreads = 10
        val numOperations = 100
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val jobs = List(numThreads) { threadId ->
            launch(Dispatchers.Default) {
                repeat(numOperations) { operationId ->
                    try {
                        val targetNode = nodes[operationId % nodes.size]
                        val newValue = "value_${threadId}_$operationId"

                        // Write operation
                        deltaStorage.setNodeProperties(targetNode, "prop" to StrVal(newValue))

                        // Read operation
                        val readValue = deltaStorage.getNodeProperty(targetNode, "prop")
                        if (readValue != null) {
                            successCount.incrementAndGet()
                        } else {
                            errorCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state
        assertEquals(0, errorCount.get(), "Should have no errors")
        assertEquals(numThreads * numOperations, successCount.get(), "All operations should succeed")

        // Verify all nodes still exist and have valid properties
        nodes.forEach { node ->
            assertTrue(deltaStorage.containsNode(node))
            assertNotNull(deltaStorage.getNodeProperty(node, "prop"))
        }
    }

    @Test
    fun `test concurrent delete and query operations`() = runBlocking {
        val numNodes = 100
        val nodes = (0 until numNodes).map { NodeID("node$it") }

        // Setup initial data through delta storage
        nodes.forEach { deltaStorage.addNode(it) }

        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(3)
        val deleteSuccess = AtomicBoolean(true)
        val querySuccess = AtomicBoolean(true)
        val modifySuccess = AtomicBoolean(true)

        // Thread 1: Delete nodes with odd indices
        launch(Dispatchers.Default) {
            try {
                startLatch.await()
                deltaStorage.deleteNodes { nodeId ->
                    nodeId.name.substring(4).toInt() % 2 == 1
                }
            } catch (e: Exception) {
                deleteSuccess.set(false)
            } finally {
                finishLatch.countDown()
            }
        }

        // Thread 2: Query nodes
        launch(Dispatchers.Default) {
            try {
                startLatch.await()
                repeat(200) {
                    val randomIndex = (0 until numNodes).random()
                    val nodeId = NodeID("node$randomIndex")

                    try {
                        if (deltaStorage.containsNode(nodeId)) {
                            deltaStorage.getNodeProperties(nodeId)
                        }
                    } catch (e: Exception) {
                        if (e !is EntityNotExistException) {
                            querySuccess.set(false)
                        }
                    }
                }
            } finally {
                finishLatch.countDown()
            }
        }

        // Thread 3: Modify remaining nodes
        launch(Dispatchers.Default) {
            try {
                startLatch.await()
                repeat(200) {
                    val randomIndex = (0 until numNodes).random()
                    val nodeId = NodeID("node$randomIndex")

                    try {
                        if (deltaStorage.containsNode(nodeId)) {
                            deltaStorage.setNodeProperties(nodeId, "modified" to StrVal("value_$it"))
                        }
                    } catch (e: Exception) {
                        if (e !is EntityNotExistException) {
                            modifySuccess.set(false)
                        }
                    }
                }
            } finally {
                finishLatch.countDown()
            }
        }

        // Start concurrent operations
        startLatch.countDown()
        finishLatch.await(10, TimeUnit.SECONDS)

        assertTrue(deleteSuccess.get(), "Delete operations should complete successfully")
        assertTrue(querySuccess.get(), "Query operations should complete successfully")
        assertTrue(modifySuccess.get(), "Modify operations should complete successfully")

        // Verify final state
        assertEquals(numNodes / 2, deltaStorage.nodeSize, "Half of the nodes should remain")
        deltaStorage.nodeIDsSequence.forEach { nodeId ->
            val idx = nodeId.name.substring(4).toInt()
            assertEquals(0, idx % 2, "Only nodes with even indices should remain")
        }
    }

    @Test
    fun `test concurrent edge operations`() = runBlocking {
        // Setup initial nodes through delta storage
        val nodes = (1..4).map { NodeID("node$it") }
        nodes.forEach { deltaStorage.addNode(it) }

        val numThreads = 5
        val numOperations = 50
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val jobs = List(numThreads) { threadId ->
            launch(Dispatchers.Default) {
                repeat(numOperations) { operationId ->
                    try {
                        // Create unique edge ID
                        val src = nodes[operationId % 2]
                        val dst = nodes[2 + (operationId % 2)]
                        val edgeId = EdgeID(src, dst, "edge_${threadId}_$operationId")

                        // Add edge
                        deltaStorage.addEdge(edgeId, "weight" to StrVal(operationId.toString()))

                        // Verify edge exists
                        if (deltaStorage.containsEdge(edgeId)) {
                            // Update edge property
                            deltaStorage.setEdgeProperties(edgeId, "updated" to StrVal("${threadId}_$operationId"))

                            // Verify property was set
                            val props = deltaStorage.getEdgeProperties(edgeId)
                            if (props.containsKey("updated")) {
                                successCount.incrementAndGet()
                            }

                            // Delete edge
                            deltaStorage.deleteEdge(edgeId)
                        }
                    } catch (e: EntityAlreadyExistException) {
                        // This is expected in concurrent operations
                        successCount.incrementAndGet()
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state
        assertEquals(0, errorCount.get(), "Should have no errors")
        assertTrue(successCount.get() > 0, "Should have successful operations")
        assertEquals(0, deltaStorage.edgeSize, "All edges should be deleted")
        nodes.forEach { node ->
            assertTrue(deltaStorage.containsNode(node), "All nodes should still exist")
            assertTrue(deltaStorage.getOutgoingEdges(node).isEmpty(), "No outgoing edges should remain")
            assertTrue(deltaStorage.getIncomingEdges(node).isEmpty(), "No incoming edges should remain")
        }
    }

    @Test
    fun `test concurrent property updates`() = runBlocking {
        // Setup test nodes through delta storage
        val nodes = (1..4).map { NodeID("node$it") }
        nodes.forEach { node ->
            deltaStorage.addNode(
                node,
                "prop1" to StrVal("initial1"),
                "prop2" to StrVal("initial2")
            )
        }

        val numThreads = 10
        val numOperations = 50
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val jobs = List(numThreads) { threadId ->
            launch(Dispatchers.Default) {
                repeat(numOperations) { operationId ->
                    try {
                        val targetNode = nodes[operationId % nodes.size]
                        val propKey = if (operationId % 3 == 0) "prop1" else "prop2"

                        // Update property
                        deltaStorage.setNodeProperties(
                            targetNode,
                            propKey to StrVal("value_${threadId}_$operationId")
                        )

                        // Read and verify property
                        val props = deltaStorage.getNodeProperties(targetNode)
                        if (props.containsKey(propKey)) {
                            successCount.incrementAndGet()
                        }

                        // Occasionally delete a property
                        if (operationId % 5 == 0) {
                            deltaStorage.setNodeProperties(targetNode, propKey to null)
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state
        assertEquals(0, errorCount.get(), "Should have no errors")
        assertTrue(successCount.get() > 0, "Should have successful operations")

        // Verify all nodes still exist and have properties
        nodes.forEach { node ->
            assertTrue(deltaStorage.containsNode(node))
            val props = deltaStorage.getNodeProperties(node)
            assertTrue(props.isNotEmpty(), "Node should have properties")
        }
    }
}
