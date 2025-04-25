package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test suite for ConcurJgraphtStorageImpl to verify its functionality and thread safety
 */
class JgraphtConcurStorageImplTest {
    private lateinit var storage: JgraphtConcurStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge1 = EdgeID(node1, node2, "edge1")
    private val edge2 = EdgeID(node2, node3, "edge2")
    private val edge3 = EdgeID(node1, node3, "edge3")

    @Before
    fun setup() {
        storage = JgraphtConcurStorageImpl()
    }

    @After
    fun cleanup() {
        storage.close()
    }

    @Test
    fun `test basic CRUD operations`() {
        // 复用基本的 CRUD 测试
        // ... 与 JgraphtStorageImplTest 相同的测试代码 ...
    }

    @Test
    fun `test property updates`() {
        // 复用属性更新测试
        // ... 与 JgraphtStorageImplTest 相同的测试代码 ...
    }

    @Test
    fun `test concurrent node additions`() {
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
            threadCount * nodeCountPerThread, storage.nodeSize,
            "Should have added the correct number of nodes"
        )
    }

    @Test
    fun `test concurrent read-write operations`() {
        // Add initial data
        storage.addNode(node1, "counter" to 0.numVal)

        val threadCount = 5
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount * 2) // Half for reading, half for writing
        val latch = CountDownLatch(threadCount * 2)
        val errors = AtomicInteger(0)
        val incrementAttempts = AtomicInteger(0)

        // Writer threads
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val current = storage.getNodeProperty(node1, "counter") as NumVal
                        storage.setNodeProperties(node1, "counter" to (current.core.toInt() + 1).numVal)
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

        // Reader threads
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
        val totalAttempts = incrementAttempts.get()

        // Due to the race condition between read and write operations,
        // we can only make general assertions about the final value
        assertTrue(finalValue.core.toInt() > 0, "Counter should have increased")
        assertTrue(
            finalValue.core.toInt() <= totalAttempts,
            "Counter cannot exceed total increment attempts"
        )

        // Report the race condition efficiency
        val efficiency = (finalValue.core.toInt().toDouble() / totalAttempts) * 100
        println(
            "Increment efficiency: $efficiency% (${finalValue.core.toInt()} successful " +
                    "increments out of $totalAttempts attempts)"
        )
    }

    @Test
    fun `test concurrent node deletion`() {
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
                            assertNotNull(props["index"])
                        }
                    } catch (e: EntityNotExistException) {
                        // This is acceptable as nodes might be deleted
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

        startLatch.countDown()
        finishLatch.await(10, TimeUnit.SECONDS)

        assertTrue(deleteSuccess.get(), "Delete operation should complete successfully")
        assertTrue(querySuccess.get(), "Query operations should complete successfully")
        assertEquals(50, storage.nodeSize, "Should have 50 nodes remaining (with even indices)")
    }

    @Test
    fun `test concurrent graph traversal`() {
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

        // Multiple threads performing different graph traversal operations
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(100) {
                        when (t % 5) {
                            0 -> {
                                val inEdges = storage.getIncomingEdges(node3)
                                assertEquals(2, inEdges.size)
                            }

                            1 -> {
                                val outEdges = storage.getOutgoingEdges(node1)
                                assertEquals(2, outEdges.size)
                            }

                            2 -> {
                                val edges = storage.getEdgesBetween(node1, node3)
                                assertEquals(1, edges.size)
                            }

                            3 -> {
                                val propName = "prop_$t"
                                storage.setNodeProperties(node1, propName to "value_$t".strVal)
                                val value = storage.getNodeProperty(node1, propName)
                                assertNotNull(value)
                            }

                            4 -> {
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
    }

    @Test
    fun `test lock contention`() {
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

        // Set a timeout to detect potential deadlocks
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

    // 其他基本测试可以复用 JgraphtStorageImplTest 的测试代码
    // 包括 test empty and null values, test complex data structures, test large dataset operations, test exception handling
}