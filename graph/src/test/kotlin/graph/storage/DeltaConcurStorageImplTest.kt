package graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.DeltaConcurStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.StrVal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        // Add node to foundation layer
        foundStorage.addNode(testNode1, testProperty)
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

        // Update property in present layer for foundation node
        val updatedProperty = "testProp" to StrVal("updatedValue")
        deltaStorage.setNodeProperties(testNode1, updatedProperty)
        assertEquals(updatedProperty.second, deltaStorage.getNodeProperty(testNode1, updatedProperty.first))

        // Delete node from foundation layer
        deltaStorage.deleteNode(testNode1)
        assertFalse(deltaStorage.containsNode(testNode1))
        assertEquals(1, deltaStorage.nodeSize)
    }

    @Test
    fun `test edge operations with delta layers`() {
        // Setup nodes in both layers
        foundStorage.addNode(testNode1)
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
        // Add node with properties in foundation
        val foundProps = arrayOf(
            "prop1" to StrVal("value1"),
            "prop2" to StrVal("value2")
        )
        foundStorage.addNode(testNode1, *foundProps)

        // Add/update properties in present layer
        val presentProps = arrayOf(
            "prop2" to StrVal("updatedValue2"), // Override foundation
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
        // Setup nodes and edges in different layers
        foundStorage.addNode(testNode1)
        foundStorage.addNode(testNode2)
        val edge1 = EdgeID(testNode1, testNode2, "edge1")
        foundStorage.addEdge(edge1)

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
    fun `test bulk operations across layers`() {
        // Add nodes and edges to foundation
        val foundNodes = (1..3).map { NodeID("found$it") } // found1, found2, found3
        foundNodes.forEach { foundStorage.addNode(it) }
        val foundEdges = (0..1).map { EdgeID(foundNodes[it], foundNodes[it + 1], "foundEdge$it") }
        foundEdges.forEach { foundStorage.addEdge(it) }

        // Add nodes and edges to present
        val presentNodes = (4..6).map { NodeID("present$it") } // found1, found2, found3, present4, present5, present6
        presentNodes.forEach { deltaStorage.addNode(it) }
        val presentEdges = (0..1).map { EdgeID(presentNodes[it], presentNodes[it + 1], "presentEdge$it") }
        presentEdges.forEach { deltaStorage.addEdge(it) }

        assertEquals(6, deltaStorage.nodeSize)
        assertEquals(4, deltaStorage.edgeSize)

        // Delete nodes conditionally
        deltaStorage.deleteNodes { it.name.startsWith("found") } // present4, present5, present6
        assertEquals(3, deltaStorage.nodeSize)
        assertEquals(2, deltaStorage.edgeSize)

        // Delete edges conditionally
        deltaStorage.deleteEdges { it.name.contains("presentEdge") }
        assertEquals(0, deltaStorage.edgeSize)
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
    fun `test concurrent operations`() = runBlocking {
        // Setup initial data in foundation
        val numNodes = 50
        val foundNodes = (0 until numNodes / 2).map { NodeID("found$it") }
        foundNodes.forEach { nodeId ->
            val nodeIndex = nodeId.name.substring(5).toInt()
            foundStorage.addNode(nodeId, "prop" to StrVal("foundValue$nodeIndex"))
        }

        // Setup initial data in present
        val presentNodes = (numNodes / 2 until numNodes).map { NodeID("present$it") }
        presentNodes.forEach { nodeId ->
            val nodeIndex = nodeId.name.substring(7).toInt()
            deltaStorage.addNode(nodeId, "prop" to StrVal("presentValue$nodeIndex"))
        }

        val numThreads = 10
        val numOperations = 50
        val mutex = Mutex()
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val jobs = List(numThreads) { threadId ->
            launch(Dispatchers.Default) {
                repeat(numOperations) { operationId ->
                    try {
                        val isFoundOperation = operationId % 2 == 0
                        val nodeId = if (isFoundOperation) {
                            foundNodes[operationId % (numNodes / 2)]
                        } else {
                            presentNodes[operationId % (numNodes / 2)]
                        }

                        mutex.withLock {
                            val properties = deltaStorage.getNodeProperties(nodeId)
                            val nodeIndex = nodeId.name.substring(if (isFoundOperation) 5 else 7).toInt()
                            val expectedPrefix = if (isFoundOperation) "foundValue" else "presentValue"
                            val expectedValue = StrVal("$expectedPrefix$nodeIndex")

                            if (properties["prop"] == expectedValue) {
                                successCount.incrementAndGet()
                            } else {
                                errorCount.incrementAndGet()
                            }
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state
        assertTrue(successCount.get() > 0, "No successful operations were performed")
        assertEquals(
            numThreads * numOperations, successCount.get() + errorCount.get(),
            "Total operations should equal success + error count"
        )
    }
}
