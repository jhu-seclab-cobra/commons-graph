package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for MapDBConcurStorageImpl: adjacency, metadata, lifecycle, and transferTo.
 *
 * - `getIncomingEdges returns correct edge set`
 * - `getIncomingEdges throws EntityNotExistException for missing node`
 * - `getOutgoingEdges returns correct edge set`
 * - `self loop edge appears in both incoming and outgoing`
 * - `setMeta stores and getMeta retrieves value`
 * - `setMeta with null removes metadata entry`
 * - `getMeta returns null for nonexistent key`
 * - `clear removes all nodes edges and metadata`
 * - `transferTo copies nodes edges and metadata to target`
 */
internal class MapDBConcurStorageImplTest {
    private lateinit var storage: IStorage

    @BeforeTest
    fun setUp() {
        storage = MapDBConcurStorageImpl { memoryDB() }
    }

    @AfterTest
    fun tearDown() {
        (storage as AutoCloseable).close()
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
    }

    @Test
    fun `getMeta returns null for nonexistent key`() {
        assertNull(storage.getMeta("nonexistent"))
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

    // -- transferTo --

    @Test
    fun `transferTo copies nodes edges and metadata to target`() {
        val n1 = storage.addNode(mapOf("a" to 1.intVal))
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "rel", mapOf("w" to 3.intVal))
        storage.setMeta("version", "1".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("1", (target.getMeta("version") as StrVal).core)
    }
}
