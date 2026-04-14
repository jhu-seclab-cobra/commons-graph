/**
 * White-box tests for JGraphT concurrent-specific internal behavior of [JgraphtConcurStorageImpl].
 *
 * - `getNodeProperties returns defensive copy not internal reference`
 * - `getEdgeProperties returns defensive copy not internal reference`
 * - `modifying returned properties does not affect internal state`
 * - `nodeIDs returns snapshot under read lock`
 * - `pseudograph supports parallel edges under concurrent impl`
 * - `concurrent deleteNode does not cause ConcurrentModificationException`
 * - `no deadlock under mixed read-write operations`
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull

internal class JgraphtConcurStorageImplWhiteBoxTest {
    private lateinit var storage: JgraphtConcurStorageImpl

    @BeforeTest
    fun setUp() {
        storage = JgraphtConcurStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // -- Defensive copy behavior --

    @Test
    fun `getNodeProperties returns defensive copy not internal reference`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))
        val props1 = storage.getNodeProperties(node1)
        val props2 = storage.getNodeProperties(node1)
        assertNotSame(props1, props2)
        assertEquals(props1, props2)
    }

    @Test
    fun `getEdgeProperties returns defensive copy not internal reference`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e", mapOf("x" to "y".strVal))
        val props1 = storage.getEdgeProperties(e)
        val props2 = storage.getEdgeProperties(e)
        assertNotSame(props1, props2)
        assertEquals(props1, props2)
    }

    @Test
    fun `modifying returned properties does not affect internal state`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))
        val props = storage.getNodeProperties(node1) as MutableMap
        props["a"] = 999.numVal
        props["injected"] = "hack".strVal

        val actual = storage.getNodeProperties(node1)
        assertEquals(1, (actual["a"] as NumVal).core)
        assertNull(actual["injected"])
    }

    // -- Snapshot behavior under read lock --

    @Test
    fun `nodeIDs returns snapshot under read lock`() {
        storage.addNode()
        val snapshot = storage.nodeIDs
        storage.addNode()
        assertEquals(1, snapshot.size)
        assertEquals(2, storage.nodeIDs.size)
    }

    // -- Pseudograph parallel edges --

    @Test
    fun `pseudograph supports parallel edges under concurrent impl`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "type_a")
        storage.addEdge(n1, n2, "type_b")
        assertEquals(2, storage.getOutgoingEdges(n1).size)
        assertEquals(2, storage.getIncomingEdges(n2).size)
    }

    // -- Concurrent safety --

    @Test
    fun `concurrent deleteNode does not cause ConcurrentModificationException`() {
        val nodeCount = 100
        val nodeIds = (0 until nodeCount).map { storage.addNode() }
        for (i in 0 until nodeCount - 1) {
            storage.addEdge(nodeIds[i], nodeIds[i + 1], "e$i")
        }

        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val errors = AtomicInteger(0)

        for (t in 0 until 4) {
            executor.submit {
                try {
                    for (i in (t * 25) until ((t + 1) * 25)) {
                        try {
                            storage.deleteNode(nodeIds[i])
                        } catch (e: EntityNotExistException) {
                            // acceptable
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertEquals(0, errors.get())
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `no deadlock under mixed read-write operations`() {
        val node1 = storage.addNode(mapOf("counter" to 0.numVal))
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "e12")

        val threadCount = 10
        val opsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val timeout = AtomicBoolean(false)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) { i ->
                        when (i % 4) {
                            0 -> storage.nodeIDs
                            1 -> storage.getNodeProperties(node1)
                            2 -> storage.setNodeProperties(node1, mapOf("counter" to i.numVal))
                            3 -> storage.getOutgoingEdges(node1)
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(15, TimeUnit.SECONDS)
        if (!completed) timeout.set(true)
        executor.shutdownNow()

        assertFalse(timeout.get(), "Should not deadlock")
        assertEquals(0, errors.get())
    }
}
