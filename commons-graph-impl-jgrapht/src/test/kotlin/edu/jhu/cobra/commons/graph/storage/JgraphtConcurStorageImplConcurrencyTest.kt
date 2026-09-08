package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * Black-box tests for JgraphtConcurStorageImpl: thread safety under concurrent access.
 *
 * - `concurrent node additions produce correct total count`
 * - `concurrent read-write operations do not produce errors`
 * - `concurrent node deletion completes without errors`
 * - `concurrent graph traversal reads consistent adjacency`
 * - `lock contention under heavy read-write does not deadlock`
 */
internal class JgraphtConcurStorageImplConcurrencyTest {
    private lateinit var storage: IStorage

    @BeforeTest
    fun setUp() {
        storage = JgraphtConcurStorageImpl()
    }

    // -- concurrency tests --

    @Test
    fun `concurrent node additions produce correct total count`() {
        val threadCount = 10
        val nodesPerThread = 100
        val errors = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until nodesPerThread) {
                        storage.addNode(mapOf("thread" to t.toString().strVal, "index" to i.intVal))
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

        assertEquals(0, errors.get())
        assertEquals(threadCount * nodesPerThread, storage.nodeIDs.size)
    }

    @Test
    fun `concurrent read-write operations do not produce errors`() {
        val node1 = storage.addNode(mapOf("counter" to 0.intVal))
        val threadCount = 5
        val iterations = 100
        val executor = Executors.newFixedThreadPool(threadCount * 2)
        val latch = CountDownLatch(threadCount * 2)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterations) {
                        val current = storage.getNodeProperties(node1)["counter"] as IntVal
                        storage.setNodeProperties(node1, mapOf("counter" to (current.core.toInt() + 1).intVal))
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterations) {
                        val value = storage.getNodeProperties(node1)["counter"] as IntVal
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

        assertEquals(0, errors.get())
        val finalValue = (storage.getNodeProperties(node1)["counter"] as IntVal).core.toInt()
        assertTrue(finalValue > 0)
    }

    @Test
    fun `concurrent node deletion completes without errors`() {
        val nodeIds = (0 until 100).map { storage.addNode(mapOf("index" to it.intVal)) }
        val oddNodes = nodeIds.filterIndexed { idx, _ -> idx % 2 == 1 }

        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(2)
        val deleteSuccess = AtomicBoolean(true)
        val querySuccess = AtomicBoolean(true)

        Thread {
            try {
                startLatch.await()
                oddNodes.forEach { storage.deleteNode(it) }
            } catch (e: Exception) {
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
                            assertNotNull(storage.getNodeProperties(nodeId)["index"])
                        }
                    } catch (e: EntityNotExistException) {
                        // acceptable: deleted between containsNode and getNodeProperties
                    }
                }
            } catch (e: Exception) {
                querySuccess.set(false)
            } finally {
                finishLatch.countDown()
            }
        }.start()

        startLatch.countDown()
        finishLatch.await(10, TimeUnit.SECONDS)

        assertTrue(deleteSuccess.get())
        assertTrue(querySuccess.get())
        assertEquals(50, storage.nodeIDs.size)
    }

    @Test
    fun `concurrent graph traversal reads consistent adjacency`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        storage.addEdge(n1, n2, "e1")
        storage.addEdge(n2, n3, "e2")
        storage.addEdge(n1, n3, "e3")

        val threadCount = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(100) {
                        assertEquals(2, storage.getIncomingEdges(n3).size)
                        assertEquals(2, storage.getOutgoingEdges(n1).size)
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
        assertEquals(0, errors.get())
    }

    @Test
    fun `lock contention under heavy read-write does not deadlock`() {
        val node1 = storage.addNode(mapOf("counter" to 0.intVal))
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
                            assertNotNull(storage.getNodeProperties(node1)["counter"])
                        } catch (e: EntityNotExistException) {
                            // acceptable
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
                            val current = (storage.getNodeProperties(node1)["counter"] as? IntVal)?.core ?: 0
                            storage.setNodeProperties(node1, mapOf("counter" to (current.toInt() + 1).intVal))
                            if (i % 10 == 0) {
                                val tempId = storage.addNode(mapOf("temp" to true.boolVal))
                                storage.deleteNode(tempId)
                            }
                        } catch (e: EntityNotExistException) {
                            // acceptable
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(30, TimeUnit.SECONDS)
        if (!completed) timeoutOccurred.set(true)
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertFalse(timeoutOccurred.get(), "Should not deadlock")
        assertEquals(0, errors.get())
    }
}
