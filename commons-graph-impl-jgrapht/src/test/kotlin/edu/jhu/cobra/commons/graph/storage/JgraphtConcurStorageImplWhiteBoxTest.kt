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

    // -- getNodeProperty single-key access --

    @Test
    fun `test getNodeProperty returns existing property`() {
        val node1 = storage.addNode(mapOf("color" to "red".strVal))

        val value = storage.getNodeProperty(node1, "color")

        assertEquals("red".strVal, value)
    }

    @Test
    fun `test getNodeProperty returns null for absent property`() {
        val node1 = storage.addNode(mapOf("color" to "red".strVal))

        val value = storage.getNodeProperty(node1, "missing")

        assertNull(value)
    }

    @Test
    fun `test getNodeProperty throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(999, "key") }
    }

    // -- getEdgeProperty single-key access --

    @Test
    fun `test getEdgeProperty returns existing property`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "link", mapOf("weight" to 42.numVal))

        val value = storage.getEdgeProperty(edge12, "weight")

        assertEquals(42.numVal, value)
    }

    @Test
    fun `test getEdgeProperty returns null for absent property`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "link", mapOf("weight" to 42.numVal))

        val value = storage.getEdgeProperty(edge12, "missing")

        assertNull(value)
    }

    @Test
    fun `test getEdgeProperty throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperty(999, "key") }
    }

    // -- transferTo copies all data --

    @Test
    fun `test transferTo copies all data`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))
        val node2 = storage.addNode(mapOf("b" to 2.numVal))
        storage.addEdge(node1, node2, "rel", mapOf("w" to 3.numVal))
        storage.setMeta("version", 7.numVal)

        val target = JgraphtStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals(7.numVal, target.getMeta("version"))
        target.close()
    }

    @Test
    fun `test transferTo throws AccessClosedStorageException when closed`() {
        val target = JgraphtStorageImpl()
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.transferTo(target) }
        target.close()
    }

    // -- getEdgeStructure --

    @Test
    fun `test getEdgeStructure returns correct source`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "link")

        assertEquals(node1, storage.getEdgeStructure(edge12).src)
    }

    @Test
    fun `test getEdgeStructure returns correct destination`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "link")

        assertEquals(node2, storage.getEdgeStructure(edge12).dst)
    }

    @Test
    fun `test getEdgeStructure returns correct type`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "depends_on")

        assertEquals("depends_on", storage.getEdgeStructure(edge12).tag)
    }

    // -- Closed-state checks for all remaining operations --

    @Test
    fun `test getNodeProperty throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode(mapOf("x" to 1.numVal))
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperty(node1, "x") }
    }

    @Test
    fun `test getEdgeProperty throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperty(edge12, "weight") }
    }

    @Test
    fun `test setEdgeProperties throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setEdgeProperties(edge12, mapOf("k" to 1.numVal)) }
    }

    @Test
    fun `test deleteEdge throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.deleteEdge(edge12) }
    }

    @Test
    fun `test getEdgeStructure throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeStructure(edge12) }
    }

    @Test
    fun `test getIncomingEdges throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getIncomingEdges(node1) }
    }

    @Test
    fun `test getOutgoingEdges throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getOutgoingEdges(node1) }
    }

    @Test
    fun `test getEdgeProperties throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperties(edge12) }
    }

    @Test
    fun `test getNodeProperties throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(node1) }
    }

    @Test
    fun `test setNodeProperties throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(node1, mapOf("k" to 1.numVal)) }
    }

    @Test
    fun `test deleteNode throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(node1) }
    }

    @Test
    fun `test metaNames throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
    }

    @Test
    fun `test getMeta throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("key") }
    }

    @Test
    fun `test setMeta throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("key", 1.numVal) }
    }

    @Test
    fun `test edgeIDs throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
    }

    @Test
    fun `test containsEdge throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(0) }
    }

    // -- Entity-not-exist branches --

    @Test
    fun `test getNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(999) }
    }

    @Test
    fun `test getEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(999) }
    }

    @Test
    fun `test setEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.setEdgeProperties(999, mapOf("k" to 1.numVal)) }
    }

    @Test
    fun `test deleteEdge throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(999) }
    }

    @Test
    fun `test getEdgeStructure throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeStructure(999) }
    }

    @Test
    fun `test getIncomingEdges throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(999) }
    }

    @Test
    fun `test getOutgoingEdges throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(999) }
    }

    @Test
    fun `test setNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.setNodeProperties(999, mapOf("k" to 1.numVal)) }
    }

    @Test
    fun `test deleteNode throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(999) }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when src missing`() {
        val node2 = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(999, node2, "rel") }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when dst missing`() {
        val node1 = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(node1, 999, "rel") }
    }

    // -- transferTo id remapping: edges connect correctly using remapped node IDs --

    @Test
    fun `test transferTo with multiple nodes and edges uses remapped IDs`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))
        val node2 = storage.addNode(mapOf("b" to 2.numVal))
        storage.addEdge(node1, node2, "link", mapOf("w" to 3.numVal))
        storage.setMeta("meta", "val".strVal)

        val target = JgraphtStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("val".strVal, target.getMeta("meta"))
        val tEdge = target.edgeIDs.first()
        assertTrue(target.getEdgeStructure(tEdge).src in target.nodeIDs)
        assertTrue(target.getEdgeStructure(tEdge).dst in target.nodeIDs)
        target.close()
    }
}
