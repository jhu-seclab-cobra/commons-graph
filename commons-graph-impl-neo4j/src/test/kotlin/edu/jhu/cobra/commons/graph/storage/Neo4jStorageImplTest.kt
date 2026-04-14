/**
 * Black-box IStorage contract tests for [Neo4jStorageImpl].
 *
 * - `empty storage has no nodes or edges`
 * - `addNode with properties returns valid ID and stores properties`
 * - `addNode without properties returns valid ID with empty property map`
 * - `containsNode returns true for existing node`
 * - `containsNode returns false for nonexistent node`
 * - `nodeIDs returns all added node IDs`
 * - `getNodeProperties returns stored properties`
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
 * - `addEdge throws EntityNotExistException when src or dst missing`
 * - `containsEdge returns true for existing edge`
 * - `containsEdge returns false for nonexistent edge`
 * - `getEdgeStructure returns correct src dst and tag`
 * - `getEdgeStructure throws EntityNotExistException for missing edge`
 * - `getEdgeProperties returns stored properties`
 * - `getEdgeProperty returns value for existing property`
 * - `getEdgeProperty returns null for absent property on existing edge`
 * - `setEdgeProperties updates existing and adds new properties`
 * - `setEdgeProperties with null value removes that property`
 * - `deleteEdge removes edge from storage`
 * - `deleteEdge throws EntityNotExistException for missing edge`
 * - `getIncomingEdges returns correct edge set`
 * - `getIncomingEdges throws EntityNotExistException for missing node`
 * - `getOutgoingEdges returns correct edge set`
 * - `self loop edge appears in both incoming and outgoing`
 * - `setMeta stores and getMeta retrieves value`
 * - `setMeta with null removes metadata entry`
 * - `getMeta returns null for nonexistent key`
 * - `clear removes all nodes edges and metadata`
 * - `close then operations throw AccessClosedStorageException`
 * - `transferTo copies nodes edges and metadata to target`
 * - `invalid property name throws InvalidPropNameException`
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class Neo4jStorageImplTest {
    private lateinit var storage: IStorage
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("neo4j-test")
        storage = Neo4jStorageImpl(tempDir)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        tempDir.toFile().deleteRecursively()
    }

    // -- empty storage --

    @Test
    fun `empty storage has no nodes or edges`() {
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    // -- addNode --

    @Test
    fun `addNode with properties returns valid ID and stores properties`() {
        val id = storage.addNode(mapOf("prop1" to "value1".strVal))
        assertTrue(storage.containsNode(id))
        assertEquals("value1", (storage.getNodeProperties(id)["prop1"] as StrVal).core)
    }

    @Test
    fun `addNode without properties returns valid ID with empty property map`() {
        val id = storage.addNode()
        assertTrue(storage.containsNode(id))
        assertTrue(storage.getNodeProperties(id).isEmpty())
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

    // -- getNodeProperties --

    @Test
    fun `getNodeProperties returns stored properties`() {
        val id = storage.addNode(mapOf("a" to "v1".strVal, "b" to "v2".strVal))
        val props = storage.getNodeProperties(id)
        assertEquals(2, props.size)
        assertEquals("v1", (props["a"] as StrVal).core)
        assertEquals("v2", (props["b"] as StrVal).core)
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
        val id = storage.addNode(mapOf("a" to "v1".strVal))
        storage.setNodeProperties(id, mapOf("a" to "updated".strVal, "b" to 42.numVal))
        val props = storage.getNodeProperties(id)
        assertEquals("updated", (props["a"] as StrVal).core)
        assertEquals(42, (props["b"] as NumVal).core)
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
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `deleteNode cascades deletion to all incident edges`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e")
        storage.deleteNode(n1)
        assertFalse(storage.containsEdge(e))
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
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to "v".strVal))
        assertTrue(storage.containsEdge(e))
        assertEquals("v", (storage.getEdgeProperties(e)["w"] as StrVal).core)
    }

    @Test
    fun `addEdge throws EntityNotExistException when src or dst missing`() {
        assertFailsWith<EntityNotExistException> { storage.addEdge(-1, -2, "edge") }
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

    // -- getEdgeStructure --

    @Test
    fun `getEdgeStructure returns correct src dst and tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "FOLLOWS")
        val s = storage.getEdgeStructure(e)
        assertEquals(n1, s.src)
        assertEquals(n2, s.dst)
        assertEquals("FOLLOWS", s.tag)
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
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to "v".strVal))
        assertEquals("v", (storage.getEdgeProperties(e)["w"] as StrVal).core)
    }

    // -- getEdgeProperty --

    @Test
    fun `getEdgeProperty returns value for existing property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to "heavy".strVal))
        assertEquals("heavy", (storage.getEdgeProperty(e, "w") as StrVal).core)
    }

    @Test
    fun `getEdgeProperty returns null for absent property on existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertNull(storage.getEdgeProperty(e, "missing"))
    }

    // -- setEdgeProperties --

    @Test
    fun `setEdgeProperties updates existing and adds new properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.setEdgeProperties(e, mapOf("w" to 42.numVal))
        assertEquals(42, (storage.getEdgeProperties(e)["w"] as NumVal).core)
    }

    @Test
    fun `setEdgeProperties with null value removes that property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal, "z" to "w".strVal))
        storage.setEdgeProperties(e, mapOf("x" to null))
        assertNull(storage.getEdgeProperties(e)["x"])
        assertEquals("w", (storage.getEdgeProperties(e)["z"] as StrVal).core)
    }

    // -- deleteEdge --

    @Test
    fun `deleteEdge removes edge from storage`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertFalse(storage.containsEdge(e))
        assertEquals(0, storage.edgeIDs.size)
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
        val e = storage.addEdge(n1, n2, "rel")
        assertEquals(1, storage.getIncomingEdges(n2).size)
        assertTrue(e in storage.getIncomingEdges(n2))
    }

    @Test
    fun `getIncomingEdges throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(-1) }
    }

    @Test
    fun `getOutgoingEdges returns correct edge set`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertEquals(1, storage.getOutgoingEdges(n1).size)
        assertTrue(e in storage.getOutgoingEdges(n1))
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
        storage.addNode()
        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    // -- close --

    @Test
    fun `close then operations throw AccessClosedStorageException`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
    }

    // -- transferTo --

    @Test
    fun `transferTo copies nodes edges and metadata to target`() {
        val n1 = storage.addNode(mapOf("label" to "A".strVal))
        val n2 = storage.addNode(mapOf("label" to "B".strVal))
        storage.addEdge(n1, n2, "CONNECTS", mapOf("since" to "2024".strVal))
        storage.setMeta("version", "1".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("1", target.getMeta("version")?.core)
        target.close()
    }

    // -- Neo4j-specific: reserved property name --

    @Test
    fun `invalid property name throws InvalidPropNameException`() {
        val id = storage.addNode()
        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(id, mapOf("__meta_id__" to "value".strVal))
        }
    }
}
