/**
 * White-box tests for Neo4j concurrent-specific internal behavior of [Neo4jConcurStorageImpl].
 *
 * - `addNode populates node mapping cache`
 * - `deleteNode removes from node mapping cache`
 * - `addEdge populates edge mapping cache`
 * - `deleteNode removes associated edges from edge mapping`
 * - `writeTx rolls back on exception`
 * - `init block loads existing nodes and edges from database`
 * - `getNodeProperties excludes META_ID property`
 * - `meta operations under lock`
 * - `setMeta null removes entry`
 * - `meta not persisted across storage instances`
 * - `close acquires write lock and sets isClosed`
 * - `clear empties all structures under write lock`
 * - `addEdge missing src throws EntityNotExistException`
 * - `deleteNode nonexistent throws EntityNotExistException`
 * - `self loop edge appears in both incoming and outgoing`
 * - `concurrent deleteNode does not cause errors`
 * - `no deadlock under mixed read-write operations`
 * - `nodeIDs returns snapshot not live view`
 * - `setEdgeProperties sets property on existing edge`
 * - `setEdgeProperties with null removes property`
 * - `setEdgeProperties nonexistent throws EntityNotExistException`
 * - `deleteEdge removes edge from edge mapping cache`
 * - `deleteEdge nonexistent throws EntityNotExistException`
 * - `deleteEdge leaves nodes intact`
 * - `getEdgeStructure returns correct src dst and tag`
 * - `getEdgeStructure nonexistent throws EntityNotExistException`
 * - `getNodeProperties nonexistent throws EntityNotExistException`
 * - `getEdgeProperties nonexistent throws EntityNotExistException`
 * - `setNodeProperties nonexistent throws EntityNotExistException`
 * - `transferTo copies nodes edges and meta to target`
 * - `transferTo throws AccessClosedStorageException when closed`
 * - `double close does not throw`
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.InvalidPropNameException
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class Neo4jConcurStorageImplWhiteBoxTest {
    private lateinit var storage: Neo4jConcurStorageImpl
    private lateinit var graphDir: Path

    @BeforeTest
    fun setUp() {
        graphDir = Files.createTempDirectory("neo4j-concur-wb-test")
        storage = Neo4jConcurStorageImpl(graphDir)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        graphDir.toFile().deleteRecursively()
    }

    // -- Node/edge mapping cache consistency --

    @Test
    fun `addNode populates node mapping cache`() {
        val n = storage.addNode()
        assertTrue(storage.containsNode(n))
        assertEquals(setOf(n), storage.nodeIDs)
    }

    @Test
    fun `deleteNode removes from node mapping cache`() {
        val n = storage.addNode()
        storage.deleteNode(n)
        assertFalse(storage.containsNode(n))
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `addEdge populates edge mapping cache`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(e))
        assertEquals(setOf(e), storage.edgeIDs)
    }

    @Test
    fun `deleteNode removes associated edges from edge mapping`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "e12")
        val e13 = storage.addEdge(n1, n3, "e13")
        val e23 = storage.addEdge(n2, n3, "e23")

        storage.deleteNode(n1)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e13))
        assertTrue(storage.containsEdge(e23))
    }

    // -- Transaction semantics --

    @Test
    fun `writeTx rolls back on exception`() {
        val n = storage.addNode(mapOf("before" to "original".strVal))
        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(n, mapOf("__meta_id__" to "hack".strVal))
        }
        val props = storage.getNodeProperties(n)
        assertEquals("original", (props["before"] as StrVal).core)
        assertNull(props["__meta_id__"])
    }

    // -- Init block loads existing data --

    @Test
    fun `init block loads existing nodes and edges from database`() {
        val n1 = storage.addNode(mapOf("data" to "d1".strVal))
        val n2 = storage.addNode(mapOf("data" to "d2".strVal))
        val e = storage.addEdge(n1, n2, "link", mapOf("weight" to 1.numVal))
        storage.close()

        val reloaded = Neo4jConcurStorageImpl(graphDir)
        assertTrue(reloaded.containsNode(n1))
        assertTrue(reloaded.containsNode(n2))
        assertTrue(reloaded.containsEdge(e))
        assertEquals("d1", (reloaded.getNodeProperties(n1)["data"] as StrVal).core)
        assertEquals(1, (reloaded.getEdgeProperties(e)["weight"] as NumVal).core)
        reloaded.close()
    }

    // -- META_ID property filtering --

    @Test
    fun `getNodeProperties excludes META_ID property`() {
        val n = storage.addNode(mapOf("visible" to "yes".strVal))
        val props = storage.getNodeProperties(n)
        assertNull(props["__meta_id__"])
        assertEquals(1, props.size)
    }

    // -- Metadata under lock --

    @Test
    fun `meta operations under lock`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertTrue("version" in storage.metaNames)
    }

    @Test
    fun `setMeta null removes entry`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
        assertFalse("key" in storage.metaNames)
    }

    @Test
    fun `meta not persisted across storage instances`() {
        storage.setMeta("key", "val".strVal)
        storage.close()
        val reloaded = Neo4jConcurStorageImpl(graphDir)
        assertNull(reloaded.getMeta("key"))
        reloaded.close()
    }

    // -- Close under write lock --

    @Test
    fun `close acquires write lock and sets isClosed`() {
        storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(-1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    // -- Clear under write lock --

    @Test
    fun `clear empties all structures under write lock`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "e")
        storage.setMeta("key", "val".strVal)
        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    // -- Entity existence contracts --

    @Test
    fun `addEdge missing src throws EntityNotExistException`() {
        val dst = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(-1, dst, "e") }
    }

    @Test
    fun `deleteNode nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }

    // -- Self-loop edges --

    @Test
    fun `self loop edge appears in both incoming and outgoing`() {
        val n = storage.addNode()
        val selfEdge = storage.addEdge(n, n, "loop")
        assertTrue(selfEdge in storage.getOutgoingEdges(n))
        assertTrue(selfEdge in storage.getIncomingEdges(n))
    }

    // -- Concurrent safety --

    @Test
    fun `concurrent deleteNode does not cause errors`() {
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

        latch.await(30, TimeUnit.SECONDS)
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
        val opsPerThread = 50
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

        val completed = latch.await(30, TimeUnit.SECONDS)
        if (!completed) timeout.set(true)
        executor.shutdownNow()

        assertFalse(timeout.get(), "Should not deadlock")
        assertEquals(0, errors.get())
    }

    // -- Snapshot behavior --

    @Test
    fun `nodeIDs returns snapshot not live view`() {
        storage.addNode()
        val snapshot = storage.nodeIDs
        storage.addNode()
        assertEquals(1, snapshot.size)
        assertEquals(2, storage.nodeIDs.size)
    }

    // -- setEdgeProperties --

    @Test
    fun `setEdgeProperties sets property on existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.setEdgeProperties(e, mapOf("weight" to 42.numVal))
        assertEquals(42, (storage.getEdgeProperties(e)["weight"] as NumVal).core)
    }

    @Test
    fun `setEdgeProperties with null removes property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal, "z" to "w".strVal))
        storage.setEdgeProperties(e, mapOf("x" to null))
        val props = storage.getEdgeProperties(e)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    @Test
    fun `setEdgeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.setEdgeProperties(-1, mapOf("key" to "val".strVal))
        }
    }

    // -- deleteEdge --

    @Test
    fun `deleteEdge removes edge from edge mapping cache`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertFalse(storage.containsEdge(e))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `deleteEdge nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(-1) }
    }

    @Test
    fun `deleteEdge leaves nodes intact`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
    }

    // -- getEdgeStructure --

    @Test
    fun `getEdgeStructure returns correct src dst and tag`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val e = storage.addEdge(src, dst, "FOLLOWS")
        val structure = storage.getEdgeStructure(e)
        assertEquals(src, structure.src)
        assertEquals(dst, structure.dst)
        assertEquals("FOLLOWS", structure.tag)
    }

    @Test
    fun `getEdgeStructure nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeStructure(-1) }
    }

    // -- Entity existence for remaining operations --

    @Test
    fun `getNodeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(-1) }
    }

    @Test
    fun `getEdgeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(-1) }
    }

    @Test
    fun `setNodeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(-1, mapOf("key" to "val".strVal))
        }
    }

    // -- transferTo --

    @Test
    fun `transferTo copies nodes edges and meta to target`() {
        val n1 = storage.addNode(mapOf("label" to "A".strVal))
        val n2 = storage.addNode(mapOf("label" to "B".strVal))
        storage.addEdge(n1, n2, "LINKS", mapOf("w" to 1.numVal))
        storage.setMeta("version", "2".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("2", (target.getMeta("version") as StrVal).core)
        target.close()
    }

    @Test
    fun `transferTo throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.transferTo(NativeStorageImpl()) }
    }

    // -- double close --

    @Test
    fun `double close does not throw`() {
        storage.close()
        storage.close()
    }
}
