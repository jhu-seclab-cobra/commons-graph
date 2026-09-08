package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.listVal
import edu.jhu.cobra.commons.value.mapVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for the AbcJgraphtStorage engine through JgraphtStorageImpl: adjacency, metadata, clear, transferTo, and complex values.
 *
 * - `getIncomingEdges returns correct edge set`
 * - `getIncomingEdges returns empty set when no incoming edges`
 * - `getIncomingEdges throws EntityNotExistException for missing node`
 * - `getOutgoingEdges returns correct edge set`
 * - `getOutgoingEdges returns empty set when no outgoing edges`
 * - `getOutgoingEdges throws EntityNotExistException for missing node`
 * - `self loop edge appears in both incoming and outgoing`
 * - `setMeta stores and getMeta retrieves value`
 * - `setMeta with null removes metadata entry`
 * - `getMeta returns null for nonexistent key`
 * - `metaNames returns all metadata keys`
 * - `clear removes all nodes edges and metadata`
 * - `transferTo copies nodes edges and metadata to target`
 * - `transferTo remaps edge endpoints to target node IDs`
 * - `transferTo preserves edge properties and tag`
 * - `transferTo same instance throws IllegalArgumentException`
 * - `complex IValue types survive property round-trip`
 */
internal class AbcJgraphtStorageTest {
    private lateinit var storage: IStorage

    @BeforeTest
    fun setUp() {
        storage = JgraphtStorageImpl()
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
    fun `getOutgoingEdges returns empty set when no outgoing edges`() {
        val n = storage.addNode()
        assertTrue(storage.getOutgoingEdges(n).isEmpty())
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
        storage.setMeta("a", 1.intVal)
        storage.setMeta("b", 2.intVal)
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

    // -- transferTo --

    @Test
    fun `transferTo copies nodes edges and metadata to target`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        storage.addEdge(n1, n2, "rel", mapOf("w" to 1.intVal))
        storage.setMeta("version", "1.0".strVal)

        val target = JgraphtStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("1.0", (target.getMeta("version") as StrVal).core)
    }

    @Test
    fun `transferTo remaps edge endpoints to target node IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "rel")

        val target = JgraphtStorageImpl()
        storage.transferTo(target)

        val tEdge = target.edgeIDs.first()
        assertTrue(target.getEdgeStructure(tEdge).src in target.nodeIDs)
        assertTrue(target.getEdgeStructure(tEdge).dst in target.nodeIDs)
    }

    @Test
    fun `transferTo preserves edge properties and tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "typed", mapOf("score" to 99.intVal))

        val target = JgraphtStorageImpl()
        storage.transferTo(target)

        val tEdge = target.edgeIDs.first()
        assertEquals("typed", target.getEdgeStructure(tEdge).tag)
        assertEquals(99, (target.getEdgeProperties(tEdge)["score"] as IntVal).core)
    }

    @Test
    fun `transferTo same instance throws IllegalArgumentException`() {
        storage.addNode()
        assertFailsWith<IllegalArgumentException> { storage.transferTo(storage) }
    }

    // -- complex values --

    @Test
    fun `complex IValue types survive property round-trip`() {
        val complexValue =
            mapOf(
                "str" to "test".strVal,
                "num" to 42.intVal,
                "bool" to true.boolVal,
                "list" to listOf(1.intVal, 2.intVal, 3.intVal).listVal,
                "map" to mapOf("nested" to "value".strVal).mapVal,
            ).mapVal

        val id = storage.addNode(mapOf("complex" to complexValue, "null" to NullVal))
        val props = storage.getNodeProperties(id)
        assertEquals(complexValue, props["complex"])
        assertTrue(props["null"] is NullVal)
    }
}
