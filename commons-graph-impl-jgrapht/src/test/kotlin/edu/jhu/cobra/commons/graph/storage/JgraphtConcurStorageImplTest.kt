/**
 * Black-box IStorage contract tests for [JgraphtConcurStorageImpl].
 *
 * - `addNode with properties returns valid ID and stores properties`
 * - `addNode without properties returns valid ID with empty property map`
 * - `addNode returns unique IDs for each call`
 * - `containsNode returns true for existing node`
 * - `containsNode returns false for nonexistent node`
 * - `nodeIDs returns all added node IDs`
 * - `nodeIDs returns empty set on fresh storage`
 * - `getNodeProperties returns stored properties`
 * - `getNodeProperties returns empty map for node with no properties`
 * - `getNodeProperties throws EntityNotExistException for missing node`
 * - `getNodeProperty returns value for existing property`
 * - `getNodeProperty returns null for absent property on existing node`
 * - `getNodeProperty throws EntityNotExistException for missing node`
 * - `setNodeProperties updates existing and adds new properties`
 * - `setNodeProperties with null value removes that property`
 * - `setNodeProperties throws EntityNotExistException for missing node`
 * - `deleteNode removes node from storage`
 * - `deleteNode cascades deletion to all incident edges`
 * - `deleteNode throws EntityNotExistException for missing node`
 * - `addEdge with properties returns valid ID and stores properties`
 * - `addEdge throws EntityNotExistException when src missing`
 * - `addEdge throws EntityNotExistException when dst missing`
 * - `addEdge allows parallel edges between same node pair`
 * - `containsEdge returns true for existing edge`
 * - `containsEdge returns false for nonexistent edge`
 * - `edgeIDs returns all added edge IDs`
 * - `getEdgeStructure returns correct src dst and tag`
 * - `getEdgeStructure throws EntityNotExistException for missing edge`
 * - `getEdgeProperties returns stored properties`
 * - `getEdgeProperties throws EntityNotExistException for missing edge`
 * - `getEdgeProperty returns value for existing property`
 * - `getEdgeProperty returns null for absent property on existing edge`
 * - `getEdgeProperty throws EntityNotExistException for missing edge`
 * - `setEdgeProperties updates existing and adds new properties`
 * - `setEdgeProperties with null value removes that property`
 * - `setEdgeProperties throws EntityNotExistException for missing edge`
 * - `deleteEdge removes edge from storage`
 * - `deleteEdge throws EntityNotExistException for missing edge`
 * - `getIncomingEdges returns correct edge set`
 * - `getIncomingEdges returns empty set when no incoming edges`
 * - `getIncomingEdges throws EntityNotExistException for missing node`
 * - `getOutgoingEdges returns correct edge set`
 * - `getOutgoingEdges throws EntityNotExistException for missing node`
 * - `self loop edge appears in both incoming and outgoing`
 * - `setMeta stores and getMeta retrieves value`
 * - `setMeta with null removes metadata entry`
 * - `getMeta returns null for nonexistent key`
 * - `metaNames returns all metadata keys`
 * - `clear removes all nodes edges and metadata`
 * - `close then operations throw AccessClosedStorageException`
 * - `transferTo copies nodes edges and metadata to target`
 * - `transferTo remaps edge endpoints to target node IDs`
 * - `complex IValue types survive property round-trip`
 * - `concurrent node additions produce correct total count`
 * - `concurrent read-write operations do not produce errors`
 * - `concurrent node deletion completes without errors`
 * - `concurrent graph traversal reads consistent adjacency`
 * - `lock contention under heavy read-write does not deadlock`
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.listVal
import edu.jhu.cobra.commons.value.mapVal
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class JgraphtConcurStorageImplTest {
    private lateinit var storage: IStorage

    @BeforeTest
    fun setUp() {
        storage = JgraphtConcurStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // -- addNode --

    @Test
    fun `addNode with properties returns valid ID and stores properties`() {
        val id = storage.addNode(mapOf("k" to "v".strVal))
        assertTrue(storage.containsNode(id))
        assertEquals("v", (storage.getNodeProperties(id)["k"] as StrVal).core)
    }

    @Test
    fun `addNode without properties returns valid ID with empty property map`() {
        val id = storage.addNode()
        assertTrue(storage.containsNode(id))
        assertTrue(storage.getNodeProperties(id).isEmpty())
    }

    @Test
    fun `addNode returns unique IDs for each call`() {
        val ids = (1..10).map { storage.addNode() }.toSet()
        assertEquals(10, ids.size)
    }

    // -- containsNode --

    @Test
    fun `containsNode returns true for existing node`() {
        val id = storage.addNode()
        assertTrue(storage.containsNode(id))
    }

    @Test
    fun `containsNode returns false for nonexistent node`() {
        assertFalse(storage.containsNode(-1))
    }

    // -- nodeIDs --

    @Test
    fun `nodeIDs returns all added node IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        assertEquals(setOf(n1, n2), storage.nodeIDs)
    }

    @Test
    fun `nodeIDs returns empty set on fresh storage`() {
        assertTrue(storage.nodeIDs.isEmpty())
    }

    // -- getNodeProperties --

    @Test
    fun `getNodeProperties returns stored properties`() {
        val id = storage.addNode(mapOf("a" to 1.numVal, "b" to "x".strVal))
        val props = storage.getNodeProperties(id)
        assertEquals(1, (props["a"] as NumVal).core)
        assertEquals("x", (props["b"] as StrVal).core)
    }

    @Test
    fun `getNodeProperties returns empty map for node with no properties`() {
        val id = storage.addNode()
        assertTrue(storage.getNodeProperties(id).isEmpty())
    }

    @Test
    fun `getNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(-1) }
    }

    // -- getNodeProperty --

    @Test
    fun `getNodeProperty returns value for existing property`() {
        val id = storage.addNode(mapOf("name" to "hello".strVal))
        assertEquals("hello", (storage.getNodeProperty(id, "name") as StrVal).core)
    }

    @Test
    fun `getNodeProperty returns null for absent property on existing node`() {
        val id = storage.addNode()
        assertNull(storage.getNodeProperty(id, "missing"))
    }

    @Test
    fun `getNodeProperty throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(-1, "key") }
    }

    // -- setNodeProperties --

    @Test
    fun `setNodeProperties updates existing and adds new properties`() {
        val id = storage.addNode(mapOf("a" to 1.numVal))
        storage.setNodeProperties(id, mapOf("a" to 10.numVal, "b" to 20.numVal))
        val props = storage.getNodeProperties(id)
        assertEquals(10, (props["a"] as NumVal).core)
        assertEquals(20, (props["b"] as NumVal).core)
    }

    @Test
    fun `setNodeProperties with null value removes that property`() {
        val id = storage.addNode(mapOf("a" to 1.numVal, "b" to 2.numVal))
        storage.setNodeProperties(id, mapOf("a" to null))
        val props = storage.getNodeProperties(id)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
    }

    @Test
    fun `setNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(-1, mapOf("k" to "v".strVal))
        }
    }

    // -- deleteNode --

    @Test
    fun `deleteNode removes node from storage`() {
        val id = storage.addNode()
        storage.deleteNode(id)
        assertFalse(storage.containsNode(id))
    }

    @Test
    fun `deleteNode cascades deletion to all incident edges`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "out")
        val e31 = storage.addEdge(n3, n1, "in")
        val e23 = storage.addEdge(n2, n3, "other")

        storage.deleteNode(n1)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e31))
        assertTrue(storage.containsEdge(e23))
    }

    @Test
    fun `deleteNode throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }

    // -- addEdge --

    @Test
    fun `addEdge with properties returns valid ID and stores properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to 5.numVal))
        assertTrue(storage.containsEdge(e))
        assertEquals(5, (storage.getEdgeProperties(e)["w"] as NumVal).core)
    }

    @Test
    fun `addEdge throws EntityNotExistException when src missing`() {
        val n = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(-1, n, "rel") }
    }

    @Test
    fun `addEdge throws EntityNotExistException when dst missing`() {
        val n = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(n, -1, "rel") }
    }

    @Test
    fun `addEdge allows parallel edges between same node pair`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "a")
        val e2 = storage.addEdge(n1, n2, "b")
        assertTrue(e1 != e2)
        assertEquals(2, storage.getOutgoingEdges(n1).size)
    }

    // -- containsEdge --

    @Test
    fun `containsEdge returns true for existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(e))
    }

    @Test
    fun `containsEdge returns false for nonexistent edge`() {
        assertFalse(storage.containsEdge(-1))
    }

    // -- edgeIDs --

    @Test
    fun `edgeIDs returns all added edge IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "a")
        val e2 = storage.addEdge(n1, n2, "b")
        assertEquals(setOf(e1, e2), storage.edgeIDs)
    }

    // -- getEdgeStructure --

    @Test
    fun `getEdgeStructure returns correct src dst and tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "myTag")
        val s = storage.getEdgeStructure(e)
        assertEquals(n1, s.src)
        assertEquals(n2, s.dst)
        assertEquals("myTag", s.tag)
    }

    @Test
    fun `getEdgeStructure throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeStructure(-1) }
    }

    // -- getEdgeProperties --

    @Test
    fun `getEdgeProperties returns stored properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal))
        assertEquals("y", (storage.getEdgeProperties(e)["x"] as StrVal).core)
    }

    @Test
    fun `getEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(-1) }
    }

    // -- getEdgeProperty --

    @Test
    fun `getEdgeProperty returns value for existing property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to 42.numVal))
        assertEquals(42, (storage.getEdgeProperty(e, "w") as NumVal).core)
    }

    @Test
    fun `getEdgeProperty returns null for absent property on existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertNull(storage.getEdgeProperty(e, "missing"))
    }

    @Test
    fun `getEdgeProperty throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperty(-1, "key") }
    }

    // -- setEdgeProperties --

    @Test
    fun `setEdgeProperties updates existing and adds new properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("a" to 1.numVal))
        storage.setEdgeProperties(e, mapOf("a" to 10.numVal, "b" to 20.numVal))
        val props = storage.getEdgeProperties(e)
        assertEquals(10, (props["a"] as NumVal).core)
        assertEquals(20, (props["b"] as NumVal).core)
    }

    @Test
    fun `setEdgeProperties with null value removes that property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal, "z" to "w".strVal))
        storage.setEdgeProperties(e, mapOf("x" to null))
        val props = storage.getEdgeProperties(e)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    @Test
    fun `setEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.setEdgeProperties(-1, mapOf("k" to 1.numVal)) }
    }

    // -- deleteEdge --

    @Test
    fun `deleteEdge removes edge from storage`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertFalse(storage.containsEdge(e))
    }

    @Test
    fun `deleteEdge throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(-1) }
    }

    // -- adjacency --

    @Test
    fun `getIncomingEdges returns correct edge set`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e1 = storage.addEdge(n1, n3, "a")
        val e2 = storage.addEdge(n2, n3, "b")
        assertEquals(setOf(e1, e2), storage.getIncomingEdges(n3))
    }

    @Test
    fun `getIncomingEdges returns empty set when no incoming edges`() {
        val n = storage.addNode()
        assertTrue(storage.getIncomingEdges(n).isEmpty())
    }

    @Test
    fun `getIncomingEdges throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(-1) }
    }

    @Test
    fun `getOutgoingEdges returns correct edge set`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "a")
        val e2 = storage.addEdge(n1, n3, "b")
        assertEquals(setOf(e1, e2), storage.getOutgoingEdges(n1))
    }

    @Test
    fun `getOutgoingEdges throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(-1) }
    }

    @Test
    fun `self loop edge appears in both incoming and outgoing`() {
        val n = storage.addNode()
        val e = storage.addEdge(n, n, "self")
        assertTrue(e in storage.getOutgoingEdges(n))
        assertTrue(e in storage.getIncomingEdges(n))
    }

    // -- metadata --

    @Test
    fun `setMeta stores and getMeta retrieves value`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
    }

    @Test
    fun `setMeta with null removes metadata entry`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
        assertFalse("key" in storage.metaNames)
    }

    @Test
    fun `getMeta returns null for nonexistent key`() {
        assertNull(storage.getMeta("nonexistent"))
    }

    @Test
    fun `metaNames returns all metadata keys`() {
        storage.setMeta("a", 1.numVal)
        storage.setMeta("b", 2.numVal)
        assertEquals(setOf("a", "b"), storage.metaNames)
    }

    // -- clear --

    @Test
    fun `clear removes all nodes edges and metadata`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "e")
        storage.setMeta("key", "val".strVal)

        storage.clear()

        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    // -- close --

    @Test
    fun `close then operations throw AccessClosedStorageException`() {
        val n = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(n) }
    }

    // -- transferTo --

    @Test
    fun `transferTo copies nodes edges and metadata to target`() {
        val n1 = storage.addNode(mapOf("a" to 1.numVal))
        val n2 = storage.addNode(mapOf("b" to 2.numVal))
        storage.addEdge(n1, n2, "rel", mapOf("w" to 3.numVal))
        storage.setMeta("version", 7.numVal)

        val target = JgraphtStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals(7.numVal, target.getMeta("version"))
        target.close()
    }

    @Test
    fun `transferTo remaps edge endpoints to target node IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "link")

        val target = JgraphtStorageImpl()
        storage.transferTo(target)

        val tEdge = target.edgeIDs.first()
        assertTrue(target.getEdgeStructure(tEdge).src in target.nodeIDs)
        assertTrue(target.getEdgeStructure(tEdge).dst in target.nodeIDs)
        target.close()
    }

    // -- complex values --

    @Test
    fun `complex IValue types survive property round-trip`() {
        val complexValue = mapOf(
            "str" to "test".strVal,
            "num" to 42.numVal,
            "bool" to true.boolVal,
            "list" to listOf(1.numVal, 2.numVal).listVal,
            "map" to mapOf("nested" to "value".strVal).mapVal,
        ).mapVal

        val id = storage.addNode(mapOf("complex" to complexValue, "null" to NullVal))
        val props = storage.getNodeProperties(id)
        assertEquals(complexValue, props["complex"])
        assertTrue(props["null"] is NullVal)
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
                        storage.addNode(mapOf("thread" to t.toString().strVal, "index" to i.numVal))
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
        val node1 = storage.addNode(mapOf("counter" to 0.numVal))
        val threadCount = 5
        val iterations = 100
        val executor = Executors.newFixedThreadPool(threadCount * 2)
        val latch = CountDownLatch(threadCount * 2)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterations) {
                        val current = storage.getNodeProperties(node1)["counter"] as NumVal
                        storage.setNodeProperties(node1, mapOf("counter" to (current.core.toInt() + 1).numVal))
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

        assertEquals(0, errors.get())
        val finalValue = (storage.getNodeProperties(node1)["counter"] as NumVal).core.toInt()
        assertTrue(finalValue > 0)
    }

    @Test
    fun `concurrent node deletion completes without errors`() {
        val nodeIds = (0 until 100).map { storage.addNode(mapOf("index" to it.numVal)) }
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
                            val current = (storage.getNodeProperties(node1)["counter"] as? NumVal)?.core ?: 0
                            storage.setNodeProperties(node1, mapOf("counter" to (current.toInt() + 1).numVal))
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
