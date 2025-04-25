package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Tests for ConcurMapDBStorageImpl to verify concurrency safety and functional correctness
 */
class MapDBConcurStorageImplTest {
    private lateinit var storage: MapDBConcurStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge1 = EdgeID(node1, node2, "edge1")
    private val edge2 = EdgeID(node2, node3, "edge2")
    private val edge3 = EdgeID(node1, node3, "edge3")

    @Before
    fun setup() {
        storage = MapDBConcurStorageImpl { memoryDB() }
    }

    @After
    fun cleanup() {
        storage.close()
    }

    @Test
    fun `basic CRUD operations test`() {
        // Add a node
        storage.addNode(node1, "prop1" to "value1".strVal)
        assertTrue(storage.containsNode(node1))
        assertEquals(1, storage.nodeSize)

        // Get node properties
        val props = storage.getNodeProperties(node1)
        assertEquals(1, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)

        // Add a second node and an edge
        storage.addNode(node2)
        storage.addEdge(edge1, "edge_prop" to "edge_value".strVal)
        assertTrue(storage.containsEdge(edge1))

        // Update node properties
        storage.setNodeProperties(node1, "prop1" to "updated".strVal, "prop2" to 42.numVal)
        val updatedProps = storage.getNodeProperties(node1)
        assertEquals("updated", (updatedProps["prop1"] as StrVal).core)
        assertEquals(42, (updatedProps["prop2"] as NumVal).core)

        // Delete node and edge
        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node2))
    }

    @Test
    fun `read consistency test`() {
        // Add initial data
        storage.addNode(node1, "prop1" to "value1".strVal)

        // Get properties in one thread
        val props = storage.getNodeProperties(node1)

        // Modify properties in another thread
        val modificationDone = CountDownLatch(1)
        Thread {
            storage.setNodeProperties(node1, "prop1" to "changed".strVal)
            modificationDone.countDown()
        }.start()

        // Wait for modification to complete
        modificationDone.await(1, TimeUnit.SECONDS)

        // Verify the original map wasn't modified (defensive copy)
        assertEquals("value1", (props["prop1"] as StrVal).core)

        // Re-get should show the new value
        val newProps = storage.getNodeProperties(node1)
        assertEquals("changed", (newProps["prop1"] as StrVal).core)
    }

    @Test
    fun `concurrent node addition test`() {
        val threadCount = 10
        val nodeCountPerThread = 100
        val errors = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until nodeCountPerThread) {
                        val nodeId = NodeID("node_${t}_$i")
                        storage.addNode(nodeId, "thread" to t.toString().strVal, "index" to i.numVal)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get(), "There should be no thread errors")
        assertEquals(
            threadCount * nodeCountPerThread,
            storage.nodeSize,
            "Should have added the correct number of nodes"
        )
    }

    @Test
    fun `concurrent read-write test`() {
        // Add a node with a counter property
        storage.addNode(node1, "counter" to 0.numVal)

        val threadCount = 5
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount * 2) // Half for reading, half for writing
        val latch = CountDownLatch(threadCount * 2)
        val errors = AtomicInteger(0)

        // We'll use atomic counter to track expected increase attempts
        val incrementAttempts = AtomicInteger(0)

        // Writer threads: increment counter
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val current = storage.getNodeProperty(node1, "counter") as NumVal
                        storage.setNodeProperties(node1, "counter" to (current.core.toInt() + 1).numVal)

                        // Track each increment attempt (though some might be lost due to race conditions)
                        incrementAttempts.incrementAndGet()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // Reader threads: check counter value consistency
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val value = storage.getNodeProperty(node1, "counter") as NumVal
                        assertTrue(value.core.toInt() >= 0, "Counter value should not be negative")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get(), "There should be no thread errors")
        val finalValue = storage.getNodeProperty(node1, "counter") as NumVal
        val totalIncrementAttempts = incrementAttempts.get()

        // Test the implementation's actual behavior, acknowledging that race conditions
        // are possible between read and write operations
        assertTrue(finalValue.core.toInt() > 0, "Counter should have increased")
        assertTrue(
            finalValue.core.toInt() <= totalIncrementAttempts,
            "Counter cannot exceed total increment attempts"
        )

        // Report the race condition efficiency
        val efficiency = (finalValue.core.toInt().toDouble() / totalIncrementAttempts) * 100
        println(
            "Increment efficiency: $efficiency% (${finalValue.core.toInt()} successful " +
                    "increments out of $totalIncrementAttempts attempts)"
        )
    }

    @Test
    fun `concurrent node deletion test`() {
        // Add 100 nodes, half of which will be deleted
        for (i in 0 until 100) {
            storage.addNode(NodeID("test_node_$i"), "index" to i.numVal)
        }

        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(2)
        val deleteSuccess = AtomicBoolean(true)
        val querySuccess = AtomicBoolean(true)

        // Thread 1: Delete nodes with odd index
        Thread {
            try {
                startLatch.await()
                storage.deleteNodes { nodeId ->
                    val idx = nodeId.name.substringAfterLast("_").toInt()
                    idx % 2 == 1
                }
            } catch (e: Exception) {
                e.printStackTrace()
                deleteSuccess.set(false)
            } finally {
                finishLatch.countDown()
            }
        }.start()

        // Thread 2: Simultaneously query nodes
        Thread {
            try {
                startLatch.await()
                for (i in 0 until 100) {
                    try {
                        val nodeId = NodeID("test_node_$i")
                        if (storage.containsNode(nodeId)) {
                            val props = storage.getNodeProperties(nodeId)
                            // Ensure properties can be fully read
                            assertNotNull(props["index"])
                        }
                    } catch (e: EntityNotExistException) {
                        // This exception is acceptable (node may have been deleted by the other thread)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        querySuccess.set(false)
                        break
                    }
                }
            } finally {
                finishLatch.countDown()
            }
        }.start()

        // Start concurrent operations
        startLatch.countDown()
        finishLatch.await(10, TimeUnit.SECONDS)

        assertTrue(deleteSuccess.get(), "Delete operation should complete successfully")
        assertTrue(querySuccess.get(), "Query operations should complete successfully")
        assertEquals(50, storage.nodeSize, "Should have 50 nodes remaining (with even indices)")

        // Verify remaining nodes all have even indices
        storage.nodeIDsSequence.forEach { nodeId ->
            val idx = nodeId.name.substringAfterLast("_").toInt()
            assertEquals(0, idx % 2, "Remaining nodes should all have even indices")
        }
    }

    @Test
    fun `complex graph concurrent operations test`() {
        // Create a small graph
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

        val threadCount = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        // Multiple threads performing different graph traversal and query operations
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(100) {
                        when (t % 5) {
                            0 -> {
                                // Query incoming edges
                                val inEdges = storage.getIncomingEdges(node3)
                                assertEquals(2, inEdges.size)
                            }

                            1 -> {
                                // Query outgoing edges
                                val outEdges = storage.getOutgoingEdges(node1)
                                assertEquals(2, outEdges.size)
                            }

                            2 -> {
                                // Edges between nodes
                                val edges = storage.getEdgesBetween(node1, node3)
                                assertEquals(1, edges.size)
                            }

                            3 -> {
                                // Property operations
                                val propName = "prop_$t"
                                storage.setNodeProperties(node1, propName to "value_$t".strVal)
                                val value = storage.getNodeProperty(node1, propName)
                                assertNotNull(value)
                            }

                            4 -> {
                                // Edge property operations
                                val propName = "edge_prop_$t"
                                storage.setEdgeProperties(edge1, propName to "edge_value_$t".strVal)
                                val value = storage.getEdgeProperty(edge1, propName)
                                assertNotNull(value)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get(), "Concurrent graph operations should have no errors")
        assertTrue(storage.containsNode(node1), "Node 1 should still exist")
        assertTrue(storage.containsEdge(edge1), "Edge 1 should still exist")
    }

    @Test
    fun `bulk operations test`() {
        // Test adding, querying, and deleting large numbers of nodes and edges
        val largeCount = 1000

        // Add many nodes
        for (i in 0 until largeCount) {
            storage.addNode(NodeID("large_node_$i"), "index" to i.numVal)
        }

        assertEquals(largeCount, storage.nodeSize, "Should have the correct number of nodes")

        // Add many edges
        for (i in 0 until largeCount - 1) {
            val srcId = NodeID("large_node_$i")
            val dstId = NodeID("large_node_${i + 1}")
            storage.addEdge(EdgeID(srcId, dstId, "large_edge_$i"), "index" to i.numVal)
        }

        assertEquals(largeCount - 1, storage.edgeSize, "Should have the correct number of edges")

        // Bulk delete nodes
        val startTime = System.currentTimeMillis()
        storage.deleteNodes { nodeId ->
            nodeId.name.contains("large_node") &&
                    nodeId.name.substringAfterLast("_").toInt() >= largeCount / 2
        }
        val deleteTime = System.currentTimeMillis() - startTime

        println("Time to delete ${largeCount / 2} nodes and their associated edges: $deleteTime ms")
        assertEquals(largeCount / 2, storage.nodeSize, "Half of the nodes should remain")

        // Verify the remaining nodes are the correct ones
        for (i in 0 until largeCount / 2) {
            assertTrue(storage.containsNode(NodeID("large_node_$i")), "Node $i should exist")
        }
    }

    @Test
    fun `lock contention test`() {
        // Test if read-write locks function properly under high concurrency, without deadlocks
        storage.addNode(node1, "counter" to 0.numVal)

        val readThreads = 20   // Many read threads
        val writeThreads = 5    // Few write threads
        val readOps = 1000     // Operations per read thread
        val writeOps = 100     // Operations per write thread

        val executor = Executors.newFixedThreadPool(readThreads + writeThreads)
        val errors = AtomicInteger(0)
        val latch = CountDownLatch(readThreads + writeThreads)
        val timeoutOccurred = AtomicBoolean(false)

        // Submit read threads
        for (t in 0 until readThreads) {
            executor.submit {
                try {
                    repeat(readOps) {
                        try {
                            val props = storage.getNodeProperties(node1)
                            assertNotNull(props["counter"])
                        } catch (e: Exception) {
                            if (e !is EntityNotExistException) {
                                errors.incrementAndGet()
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Submit write threads
        for (t in 0 until writeThreads) {
            executor.submit {
                try {
                    repeat(writeOps) { i ->
                        try {
                            val current = (storage.getNodeProperty(node1, "counter") as? NumVal)?.core ?: 0
                            storage.setNodeProperties(node1, "counter" to (current.toInt() + 1).numVal)

                            // Every 10 operations, add a new node and delete it
                            if (i % 10 == 0) {
                                val tempNodeId = NodeID("temp_node_${t}_$i")
                                storage.addNode(tempNodeId, "temp" to true.boolVal)
                                storage.deleteNode(tempNodeId)
                            }
                        } catch (e: Exception) {
                            if (e !is EntityNotExistException) {
                                errors.incrementAndGet()
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Set a short timeout to detect potential deadlocks
        val completed = latch.await(30, TimeUnit.SECONDS)
        if (!completed) {
            timeoutOccurred.set(true)
            println("Test timed out, which could indicate a deadlock!")
        }

        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertFalse(timeoutOccurred.get(), "Test should not time out; if it does, there might be a deadlock")
        assertEquals(0, errors.get(), "Concurrent operations should not produce errors")
    }

    @Test
    fun `exception handling test`() {
        // Test behavior under various exceptional conditions

        // Try to add a duplicate node
        storage.addNode(node1)
        assertFailsWith<EntityAlreadyExistException> {
            storage.addNode(node1)
        }

        // Try to delete a non-existent node
        assertFailsWith<EntityNotExistException> {
            storage.deleteNode(node2)
        }

        // Try to add an edge connecting non-existent nodes
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edge1)
        }

        // Try operations after closing storage
        storage.close()
        assertFailsWith<AccessClosedStorageException> {
            storage.nodeSize
        }
        assertFailsWith<AccessClosedStorageException> {
            storage.addNode(node2)
        }
    }
}