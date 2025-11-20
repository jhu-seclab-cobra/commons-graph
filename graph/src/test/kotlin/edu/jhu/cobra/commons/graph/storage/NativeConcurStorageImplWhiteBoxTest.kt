package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * White-box tests for NativeConcurStorageImpl focusing on internal implementation details,
 * boundary conditions, state consistency, and concurrency edge cases.
 */
class NativeConcurStorageImplWhiteBoxTest {

    private lateinit var storage: NativeConcurStorageImpl

    private val node1 = StorageTestUtils.node1
    private val node2 = StorageTestUtils.node2
    private val node3 = StorageTestUtils.node3
    private val edge1 = StorageTestUtils.edge1
    private val edge2 = StorageTestUtils.edge2
    private val edge3 = StorageTestUtils.edge3

    @BeforeTest
    fun setup() {
        storage = NativeConcurStorageImpl()
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // PROPERTY UPDATE BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test setNodeProperties with empty map does not change properties`() {
        // Arrange
        storage.addNode(node1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        // Act
        storage.setNodeProperties(node1, emptyMap())

        // Assert
        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties with mixed null and non-null values`() {
        // Arrange
        storage.addNode(node1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        // Act
        storage.setNodeProperties(node1, mapOf(
            "prop1" to null,
            "prop2" to "updated".strVal,
            "prop3" to 100.numVal
        ))

        // Assert
        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertFalse(props.containsKey("prop1"))
        assertEquals("updated", (props["prop2"] as StrVal).core)
        assertEquals(100, (props["prop3"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties with empty map does not change properties`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        // Act
        storage.setEdgeProperties(edge1, emptyMap())

        // Assert
        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    // ============================================================================
    // GRAPH STRUCTURE BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test deleteEdge removes edge from graph structure leaving empty sets`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        // Act
        storage.deleteEdge(edge1)

        // Assert
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    @Test
    fun `test deleteEdge handles self-loop edge`() {
        // Arrange
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

        // Act
        storage.deleteEdge(selfLoop)

        // Assert
        assertFalse(storage.containsEdge(selfLoop))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node1).isEmpty())
    }

    @Test
    fun `test getIncomingEdges handles self-loop edge`() {
        // Arrange
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

        // Act
        val incoming = storage.getIncomingEdges(node1)

        // Assert
        assertTrue(incoming.contains(selfLoop))
    }

    @Test
    fun `test getOutgoingEdges handles self-loop edge`() {
        // Arrange
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

        // Act
        val outgoing = storage.getOutgoingEdges(node1)

        // Assert
        assertTrue(outgoing.contains(selfLoop))
    }

    @Test
    fun `test clear removes all graph structure entries`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.setMeta("key", "value".strVal)

        // Act
        storage.clear()

        // Assert
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
        storage.addNode(node1)
        storage.addNode(node2)
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    // ============================================================================
    // STATE CONSISTENCY TESTS
    // ============================================================================

    @Test
    fun `test graph structure consistency after multiple edge deletions`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

        // Act: Delete edges one by one
        storage.deleteEdge(edge1)
        assertTrue(storage.getOutgoingEdges(node1).contains(edge3))
        assertTrue(storage.getIncomingEdges(node2).isEmpty())

        storage.deleteEdge(edge3)
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node3).contains(edge2))

        storage.deleteEdge(edge2)
        assertTrue(storage.getOutgoingEdges(node2).isEmpty())
        assertTrue(storage.getIncomingEdges(node3).isEmpty())

        // Assert
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsNode(node3))
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test graph structure consistency when node has both incoming and outgoing edges`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3

        // Act & Assert: Verify both directions
        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))

        val outgoing2 = storage.getOutgoingEdges(node2)
        assertEquals(1, outgoing2.size)
        assertTrue(outgoing2.contains(edge2))

        // Act: Delete incoming edge
        storage.deleteEdge(edge1)
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
        assertTrue(storage.getOutgoingEdges(node2).contains(edge2))

        // Act: Delete outgoing edge
        storage.deleteEdge(edge2)
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
        assertTrue(storage.getOutgoingEdges(node2).isEmpty())
    }

    // ============================================================================
    // CONCURRENCY BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test concurrent node addition`() {
        // Arrange
        val threadCount = 10
        val nodesPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        // Act
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until nodesPerThread) {
                        val nodeId = NodeID("node_${t}_$i")
                        storage.addNode(nodeId, mapOf("thread" to t.toString().strVal, "index" to i.numVal))
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // Assert
        assertEquals(0, errors.get())
        assertEquals(threadCount * nodesPerThread, storage.nodeIDs.size)
    }

    @Test
    fun `test concurrent read-write operations`() {
        // Arrange
        storage.addNode(node1, mapOf("counter" to 0.numVal))
        val threadCount = 5
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount * 2)
        val latch = CountDownLatch(threadCount * 2)
        val errors = AtomicInteger(0)
        val incrementAttempts = AtomicInteger(0)

        // Act: Writer threads
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val current = storage.getNodeProperties(node1)["counter"] as NumVal
                        storage.setNodeProperties(node1, mapOf("counter" to (current.core.toInt() + 1).numVal))
                        incrementAttempts.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // Act: Reader threads
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val value = storage.getNodeProperties(node1)["counter"] as NumVal
                        assertTrue(value.core.toInt() >= 0)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // Assert
        assertEquals(0, errors.get())
        val finalValue = storage.getNodeProperties(node1)["counter"] as NumVal
        assertTrue(finalValue.core.toInt() > 0)
        assertTrue(finalValue.core.toInt() <= incrementAttempts.get())
    }

    @Test
    fun `test concurrent node deletion and query`() {
        // Arrange
        for (i in 0 until 100) {
            storage.addNode(NodeID("test_node_$i"), mapOf("index" to i.numVal))
        }
        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(2)
        val deleteSuccess = AtomicBoolean(true)
        val querySuccess = AtomicBoolean(true)

        // Act: Thread 1 - Delete nodes
        Thread {
            try {
                startLatch.await()
                for (i in 0 until 100) {
                    if (i % 2 == 1) {
                        try {
                            storage.deleteNode(NodeID("test_node_$i"))
                        } catch (e: EntityNotExistException) {
                            // Acceptable if already deleted
                        }
                    }
                }
            } catch (e: Exception) {
                deleteSuccess.set(false)
            } finally {
                finishLatch.countDown()
            }
        }.start()

        // Act: Thread 2 - Query nodes
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
                        // Acceptable if node was deleted
                    } catch (e: Exception) {
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

        // Assert
        assertTrue(deleteSuccess.get())
        assertTrue(querySuccess.get())
        val remainingCount = storage.nodeIDs.size
        assertTrue(remainingCount >= 40 && remainingCount <= 60)
    }

    @Test
    fun `test concurrent edge operations`() {
        // Arrange
        for (i in 0 until 50) {
            storage.addNode(NodeID("node_$i"))
        }
        val threadCount = 5
        val edgesPerThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        // Act
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until edgesPerThread) {
                        val srcIdx = (t * edgesPerThread + i) % 50
                        val dstIdx = (srcIdx + 1) % 50
                        val srcNode = NodeID("node_$srcIdx")
                        val dstNode = NodeID("node_$dstIdx")
                        val edgeId = EdgeID(srcNode, dstNode, "edge_${t}_$i")
                        storage.addEdge(edgeId, mapOf("thread" to t.toString().strVal))
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // Assert
        assertEquals(0, errors.get())
        assertEquals(threadCount * edgesPerThread, storage.edgeIDs.size)
    }

    @Test
    fun `test concurrent graph traversal`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        // Act
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(100) {
                        when (t % 4) {
                            0 -> {
                                val inEdges = storage.getIncomingEdges(node3)
                                assertTrue(inEdges.size >= 0)
                            }
                            1 -> {
                                val outEdges = storage.getOutgoingEdges(node1)
                                assertTrue(outEdges.size >= 0)
                            }
                            2 -> {
                                val props = storage.getNodeProperties(node1)
                                assertNotNull(props)
                            }
                            3 -> {
                                val props = storage.getEdgeProperties(edge1)
                                assertNotNull(props)
                            }
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // Assert
        assertEquals(0, errors.get())
    }

    @Test
    fun `test read-write lock behavior`() {
        // Arrange
        storage.addNode(node1, mapOf("counter" to 0.numVal))
        val readThreads = 20
        val writeThreads = 5
        val readOps = 1000
        val writeOps = 100
        val executor = Executors.newFixedThreadPool(readThreads + writeThreads)
        val errors = AtomicInteger(0)
        val latch = CountDownLatch(readThreads + writeThreads)
        val timeoutOccurred = AtomicBoolean(false)

        // Act: Submit read threads
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

        // Act: Submit write threads
        for (t in 0 until writeThreads) {
            executor.submit {
                try {
                    repeat(writeOps) {
                        try {
                            val current = (storage.getNodeProperties(node1)["counter"] as? NumVal)?.core ?: 0
                            storage.setNodeProperties(node1, mapOf("counter" to (current.toInt() + 1).numVal))
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

        val completed = latch.await(30, TimeUnit.SECONDS)
        if (!completed) {
            timeoutOccurred.set(true)
        }

        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // Assert
        assertFalse(timeoutOccurred.get(), "Test should not time out; potential deadlock detected")
        assertEquals(0, errors.get())
    }

    @Test
    fun `test concurrent metadata operations`() {
        // Arrange
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        // Act
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until 100) {
                        val key = "meta_$t"
                        storage.setMeta(key, "$t-$i".strVal)
                        val value = storage.getMeta(key)
                        assertNotNull(value)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // Assert
        assertEquals(0, errors.get())
    }

    @Test
    fun `test concurrent clear and operations`() {
        // Arrange
        for (i in 0 until 50) {
            storage.addNode(NodeID("node_$i"))
        }
        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(2)
        val errors = AtomicInteger(0)

        // Act: Thread 1 - Clear storage
        executor.submit {
            try {
                Thread.sleep(10)
                storage.clear()
            } catch (e: Exception) {
                errors.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        // Act: Thread 2 - Try to read
        executor.submit {
            try {
                for (i in 0 until 50) {
                    try {
                        val nodeId = NodeID("node_$i")
                        storage.containsNode(nodeId)
                    } catch (e: AccessClosedStorageException) {
                        break
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

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        // Assert
        assertTrue(errors.get() <= 1)
    }
}
