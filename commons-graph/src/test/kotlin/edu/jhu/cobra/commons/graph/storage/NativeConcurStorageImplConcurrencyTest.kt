package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.intVal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Black-box tests for NativeConcurStorageImpl: thread safety under concurrent access.
 *
 * - `concurrent reads do not deadlock` -- parallel read lock acquisition
 * - `concurrent writes produce consistent node count` -- parallel write lock
 * - `concurrent read-write does not corrupt state` -- mixed lock acquisition
 */
internal class NativeConcurStorageImplConcurrencyTest {
    private lateinit var storage: NativeConcurStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeConcurStorageImpl()
    }

    // region Thread safety

    @Test
    fun `concurrent reads do not deadlock`() {
        val (n1, n2, n3) = StorageFixtures.addTestNodes(storage)
        storage.addEdge(n1, n2, StorageFixtures.EDGE_TAG_1)
        storage.addEdge(n2, n3, StorageFixtures.EDGE_TAG_2)

        val threadCount = 10
        val opsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = CopyOnWriteArrayList<Exception>()
        val timedOut = AtomicBoolean(false)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) {
                        storage.containsNode(n1)
                        storage.getNodeProperties(n1)
                        storage.getOutgoingEdges(n1)
                        storage.getIncomingEdges(n2)
                    }
                } catch (e: Exception) {
                    errors.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(15, TimeUnit.SECONDS)
        executor.shutdown()
        if (!completed) timedOut.set(true)

        assertFalse(timedOut.get(), "Concurrent reads timed out, potential deadlock")
        assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
    }

    @Test
    fun `concurrent writes produce consistent node count`() {
        val threadCount = 10
        val nodesPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = CopyOnWriteArrayList<Exception>()

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(nodesPerThread) {
                        storage.addNode(mapOf("thread" to t.intVal))
                    }
                } catch (e: Exception) {
                    errors.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
        assertEquals(threadCount * nodesPerThread, storage.nodeIDs.size)
    }

    @Test
    fun `concurrent read-write does not corrupt state`() {
        val node = storage.addNode(mapOf("counter" to 0.intVal))
        val threadCount = 4
        val opsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount * 2)
        val latch = CountDownLatch(threadCount * 2)
        val errors = CopyOnWriteArrayList<Exception>()

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) {
                        val current = storage.getNodeProperties(node)["counter"] as IntVal
                        storage.setNodeProperties(node, mapOf("counter" to (current.core.toInt() + 1).intVal))
                    }
                } catch (e: Exception) {
                    errors.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) {
                        val value = storage.getNodeProperties(node)["counter"] as IntVal
                        assertTrue(value.core.toInt() >= 0)
                    }
                } catch (e: Exception) {
                    errors.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
        val finalValue = (storage.getNodeProperties(node)["counter"] as IntVal).core.toInt()
        assertTrue(finalValue > 0)
    }

    // endregion
}
