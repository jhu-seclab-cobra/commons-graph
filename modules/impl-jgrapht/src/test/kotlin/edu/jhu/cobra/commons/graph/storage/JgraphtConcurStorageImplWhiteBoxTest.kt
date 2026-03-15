package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
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

class JgraphtConcurStorageImplWhiteBoxTest {
    private lateinit var storage: JgraphtConcurStorageImpl

    @Before
    fun setup() {
        storage = JgraphtConcurStorageImpl()
    }

    @After
    fun cleanup() {
        storage.close()
    }

    // -- Defensive copy in getNodeProperties/getEdgeProperties --

    @Test
    fun `test getNodeProperties returns defensive copy via HashMap`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))

        val props1 = storage.getNodeProperties(node1)
        val props2 = storage.getNodeProperties(node1)

        assertNotSame(props1, props2)
        assertEquals(props1, props2)
    }

    @Test
    fun `test getEdgeProperties returns defensive copy via HashMap`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12", mapOf("x" to "y".strVal))

        val props1 = storage.getEdgeProperties(edge12)
        val props2 = storage.getEdgeProperties(edge12)

        assertNotSame(props1, props2)
        assertEquals(props1, props2)
    }

    @Test
    fun `test modifying returned properties does not affect internal state`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))

        val props = storage.getNodeProperties(node1) as MutableMap
        props["a"] = 999.numVal
        props["injected"] = "hack".strVal

        val actual = storage.getNodeProperties(node1)
        assertEquals(1, (actual["a"] as NumVal).core)
        assertNull(actual["injected"])
    }

    // -- deleteNode collects edges into toSet() before removal to avoid ConcurrentModificationException --

    @Test
    fun `test deleteNode with multiple incoming and outgoing edges`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge13 = storage.addEdge(node1, node3, "e13")
        val edge23 = storage.addEdge(node2, node3, "e23")

        val selfEdge = storage.addEdge(node1, node1, "self")

        storage.deleteNode(node1)

        assertFalse(storage.containsEdge(edge12))
        assertFalse(storage.containsEdge(edge13))
        assertFalse(storage.containsEdge(selfEdge))
        assertTrue(storage.containsEdge(edge23))
        assertEquals(setOf(node2, node3), storage.nodeIDs)
    }

    // -- Write lock in close() --

    @Test
    fun `test close acquires write lock and sets isClosed`() {
        val node1 = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    // -- clear() under write lock --

    @Test
    fun `test clear empties all structures under write lock`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "e12")
        storage.setMeta("key", "val".strVal)

        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    @Test
    fun `test clear on closed storage throws`() {
        storage.close()
        val fresh = JgraphtConcurStorageImpl()
        fresh.close()
        assertFailsWith<AccessClosedStorageException> { fresh.clear() }
    }

    // -- Read/write lock atomicity under concurrent access --

    @Test
    fun `test concurrent deleteNode does not cause ConcurrentModificationException`() {
        val nodeCount = 100
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until nodeCount) {
            nodeIds.add(storage.addNode())
        }
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
                            // acceptable: edge cascade may have triggered from another thread
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

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
        assertEquals(0, storage.nodeIDs.size)
    }

    // -- setProperties with null under write lock --

    @Test
    fun `test setNodeProperties null removes under write lock`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal, "b" to 2.numVal))

        storage.setNodeProperties(node1, mapOf("a" to null))

        val props = storage.getNodeProperties(node1)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
    }

    // -- Metadata under read/write lock --

    @Test
    fun `test setMeta and getMeta under locks`() {
        storage.setMeta("version", 1.numVal)

        assertEquals(1, (storage.getMeta("version") as NumVal).core)
        assertTrue("version" in storage.metaNames)
    }

    @Test
    fun `test setMeta null removes under write lock`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)

        assertNull(storage.getMeta("key"))
        assertFalse("key" in storage.metaNames)
    }

    // -- Concurrent read-write does not deadlock --

    @Test
    fun `test no deadlock under mixed read-write operations`() {
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
                    e.printStackTrace()
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

    // -- Parallel edges (pseudograph) --

    @Test
    fun `test pseudograph supports parallel edges under concurrent impl`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "type_a")
        storage.addEdge(node1, node2, "type_b")

        assertEquals(2, storage.getOutgoingEdges(node1).size)
        assertEquals(2, storage.getIncomingEdges(node2).size)
    }

    // -- nodeIDs/edgeIDs return snapshot under read lock --

    @Test
    fun `test nodeIDs returns snapshot not live view`() {
        val node1 = storage.addNode()
        val snapshot = storage.nodeIDs

        storage.addNode()

        assertEquals(1, snapshot.size)
        assertEquals(2, storage.nodeIDs.size)
    }
}
