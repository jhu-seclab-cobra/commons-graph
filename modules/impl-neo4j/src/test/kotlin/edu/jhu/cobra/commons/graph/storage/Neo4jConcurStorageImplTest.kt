package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Neo4jConcurStorageImplTest {
    private lateinit var storage: Neo4jConcurStorageImpl
    private lateinit var graphDir: Path

    @BeforeTest
    fun setup() {
        graphDir = Files.createTempDirectory("neo4j-concur-test")
        storage = Neo4jConcurStorageImpl(graphDir)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        graphDir.toFile().deleteRecursively()
    }

    @Test
    fun `test basic CRUD operations`() {
        val node1 = storage.addNode(mapOf("prop1" to "value1".strVal))
        assertTrue(storage.containsNode(node1))
        assertEquals(1, storage.nodeIDs.size)

        val props = storage.getNodeProperties(node1)
        assertEquals(1, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)

        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, "edge1", mapOf("edge_prop" to "edge_value".strVal))
        assertTrue(storage.containsEdge(edge1))

        storage.setNodeProperties(node1, mapOf("prop1" to "updated".strVal, "prop2" to 42.numVal))
        val updatedProps = storage.getNodeProperties(node1)
        assertEquals("updated", (updatedProps["prop1"] as StrVal).core)
        assertEquals(42, (updatedProps["prop2"] as NumVal).core)

        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node2))
    }

    @Test
    fun `test concurrent node additions`() {
        val threadCount = 4
        val nodeCountPerThread = 10
        val errors = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until nodeCountPerThread) {
                        storage.addNode(mapOf("thread" to t.toString().strVal, "index" to i.numVal))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(60, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get(), "There should be no thread errors")
        assertEquals(
            threadCount * nodeCountPerThread,
            storage.nodeIDs.size,
            "Should have added the correct number of nodes",
        )
    }

    @Test
    fun `test concurrent read-write operations`() {
        val node1 = storage.addNode(mapOf("counter" to 0.numVal))

        val threadCount = 3
        val iterationsPerThread = 10
        val executor = Executors.newFixedThreadPool(threadCount * 2)
        val latch = CountDownLatch(threadCount * 2)
        val errors = AtomicInteger(0)
        val incrementAttempts = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val current = storage.getNodeProperties(node1)["counter"] as NumVal
                        storage.setNodeProperties(node1, mapOf("counter" to (current.core.toInt() + 1).numVal))
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

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val value = storage.getNodeProperties(node1)["counter"] as NumVal
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

        latch.await(60, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get(), "There should be no thread errors")
        val finalValue = storage.getNodeProperties(node1)["counter"] as NumVal
        val totalAttempts = incrementAttempts.get()

        assertTrue(finalValue.core.toInt() > 0, "Counter should have increased")
        assertTrue(
            finalValue.core.toInt() <= totalAttempts,
            "Counter cannot exceed total increment attempts",
        )
    }

    @Test
    fun `test concurrent node deletion`() {
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until 20) {
            nodeIds.add(storage.addNode(mapOf("index" to i.numVal)))
        }

        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(2)
        val deleteSuccess = AtomicBoolean(true)
        val querySuccess = AtomicBoolean(true)

        val oddNodes = nodeIds.filterIndexed { index, _ -> index % 2 == 1 }

        Thread {
            try {
                startLatch.await()
                oddNodes.forEach { storage.deleteNode(it) }
            } catch (e: Exception) {
                e.printStackTrace()
                deleteSuccess.set(false)
            } finally {
                finishLatch.countDown()
            }
        }.start()

        Thread {
            try {
                startLatch.await()
                for (nodeId in nodeIds) {
                    try {
                        if (storage.containsNode(nodeId)) {
                            val props = storage.getNodeProperties(nodeId)
                            assertNotNull(props["index"])
                        }
                    } catch (e: EntityNotExistException) {
                        // acceptable
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
        finishLatch.await(30, TimeUnit.SECONDS)

        assertTrue(deleteSuccess.get(), "Delete operation should complete successfully")
        assertTrue(querySuccess.get(), "Query operations should complete successfully")
        assertEquals(10, storage.nodeIDs.size, "Should have 10 nodes remaining (with even indices)")
    }

    @Test
    fun `test concurrent graph traversal`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        storage.addEdge(node1, node2, "edge1")
        storage.addEdge(node2, node3, "edge2")
        storage.addEdge(node1, node3, "edge3")

        val threadCount = 3
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(10) {
                        when (t % 3) {
                            0 -> assertEquals(2, storage.getIncomingEdges(node3).size)
                            1 -> assertEquals(2, storage.getOutgoingEdges(node1).size)
                            2 -> {
                                val outEdges = storage.getOutgoingEdges(node1)
                                val filtered = outEdges.filter { storage.getEdgeStructure(it).dst == node3 }
                                assertEquals(1, filtered.size)
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

        latch.await(60, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get(), "Concurrent graph operations should have no errors")
    }

    @Test
    fun `test exception handling`() {
        val node1 = storage.addNode()

        assertFailsWith<EntityNotExistException> {
            storage.deleteNode(-1)
        }

        assertFailsWith<EntityNotExistException> {
            storage.addEdge(node1, -1, "edge1")
        }

        storage.close()
        assertFailsWith<AccessClosedStorageException> {
            storage.nodeIDs
        }
        assertFailsWith<AccessClosedStorageException> {
            storage.addNode()
        }
    }
}
