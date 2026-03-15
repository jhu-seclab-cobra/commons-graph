package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
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

class JgraphtConcurStorageImplTest {
    private lateinit var storage: JgraphtConcurStorageImpl

    @Before
    fun setup() {
        storage = JgraphtConcurStorageImpl()
    }

    @After
    fun cleanup() {
        storage.close()
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

        latch.await(30, TimeUnit.SECONDS)
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

        val threadCount = 5
        val iterationsPerThread = 100
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

        latch.await(30, TimeUnit.SECONDS)
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
        val nodeIds = mutableListOf<String>()
        for (i in 0 until 100) {
            nodeIds.add(storage.addNode(mapOf("index" to i.numVal)))
        }

        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(2)
        val deleteSuccess = AtomicBoolean(true)
        val querySuccess = AtomicBoolean(true)

        // Separate odd-indexed nodes for deletion
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
        finishLatch.await(10, TimeUnit.SECONDS)

        assertTrue(deleteSuccess.get(), "Delete operation should complete successfully")
        assertTrue(querySuccess.get(), "Query operations should complete successfully")
        assertEquals(50, storage.nodeIDs.size, "Should have 50 nodes remaining (with even indices)")
    }

    @Test
    fun `test concurrent graph traversal`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, "edge1")
        val edge2 = storage.addEdge(node2, node3, "edge2")
        val edge3 = storage.addEdge(node1, node3, "edge3")

        val threadCount = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

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
                                val outEdges = storage.getOutgoingEdges(node1)
                                val filtered = outEdges.filter { storage.getEdgeDst(it) == node3 }
                                assertEquals(1, filtered.size)
                            }

                            3 -> {
                                val propName = "prop_$t"
                                storage.setNodeProperties(node1, mapOf(propName to "value_$t".strVal))
                                val value = storage.getNodeProperties(node1)[propName]
                                assertNotNull(value)
                            }

                            4 -> {
                                val propName = "edge_prop_$t"
                                storage.setEdgeProperties(edge1, mapOf(propName to "edge_value_$t".strVal))
                                val value = storage.getEdgeProperties(edge1)[propName]
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
        val node1 = storage.addNode(mapOf("counter" to 0.numVal))

        val readThreads = 20
        val writeThreads = 5
        val readOps = 1000
        val writeOps = 100

        val executor = Executors.newFixedThreadPool(readThreads + writeThreads)
        val errors = AtomicInteger(0)
        val latch = CountDownLatch(readThreads + writeThreads)
        val timeoutOccurred = AtomicBoolean(false)

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

        for (t in 0 until writeThreads) {
            executor.submit {
                try {
                    repeat(writeOps) { i ->
                        try {
                            val current = (storage.getNodeProperties(node1)["counter"] as? NumVal)?.core ?: 0
                            storage.setNodeProperties(node1, mapOf("counter" to (current.toInt() + 1).numVal))

                            if (i % 10 == 0) {
                                val tempNodeId = storage.addNode(mapOf("temp" to true.boolVal))
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

        val completed = latch.await(30, TimeUnit.SECONDS)
        if (!completed) {
            timeoutOccurred.set(true)
        }

        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertFalse(timeoutOccurred.get(), "Test should not time out; if it does, there might be a deadlock")
        assertEquals(0, errors.get(), "Concurrent operations should not produce errors")
    }
}
