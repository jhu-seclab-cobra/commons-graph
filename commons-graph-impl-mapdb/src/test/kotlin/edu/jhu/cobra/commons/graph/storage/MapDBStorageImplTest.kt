/**
 * Black-box IStorage contract tests for [MapDBStorageImpl].
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
 * - `addEdge without properties returns valid ID with empty property map`
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
 * - `deleteEdge leaves endpoints intact`
 * - `deleteEdge throws EntityNotExistException for missing edge`
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
 * - `close then operations throw AccessClosedStorageException`
 * - `transferTo copies nodes edges and metadata to target`
 * - `transferTo remaps edge endpoints to target node IDs`
 * - `transferTo preserves edge properties and tag`
 * - `complex IValue types survive property round-trip`
 * - `NullVal stored and retrieved correctly`
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MapDBStorageImplTest {
    private lateinit var storage: IStorage

    @BeforeTest
    fun setUp() {
        storage = MapDBStorageImpl { memoryDB() }
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
        assertEquals(0, storage.nodeIDs.size)
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
    fun `addEdge without properties returns valid ID with empty property map`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.getEdgeProperties(e).isEmpty())
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
        val structure = storage.getEdgeStructure(e)
        assertEquals(n1, structure.src)
        assertEquals(n2, structure.dst)
        assertEquals("myTag", structure.tag)
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
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to 1.numVal))
        assertEquals(1, (storage.getEdgeProperty(e, "w") as NumVal).core)
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
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `deleteEdge leaves endpoints intact`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
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
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    // -- transferTo --

    @Test
    fun `transferTo copies nodes edges and metadata to target`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        storage.addEdge(n1, n2, "rel", mapOf("w" to 1.numVal))
        storage.setMeta("version", "1.0".strVal)

        val target = MapDBStorageImpl { memoryDB() }
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("1.0", (target.getMeta("version") as StrVal).core)
        target.close()
    }

    @Test
    fun `transferTo remaps edge endpoints to target node IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "rel")

        val target = MapDBStorageImpl { memoryDB() }
        storage.transferTo(target)

        val tEdge = target.edgeIDs.first()
        assertTrue(target.getEdgeStructure(tEdge).src in target.nodeIDs)
        assertTrue(target.getEdgeStructure(tEdge).dst in target.nodeIDs)
        target.close()
    }

    @Test
    fun `transferTo preserves edge properties and tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "typed", mapOf("score" to 99.numVal))

        val target = MapDBStorageImpl { memoryDB() }
        storage.transferTo(target)

        val tEdge = target.edgeIDs.first()
        assertEquals("typed", target.getEdgeStructure(tEdge).tag)
        assertEquals(99, (target.getEdgeProperties(tEdge)["score"] as NumVal).core)
        target.close()
    }

    // -- complex values --

    @Test
    fun `complex IValue types survive property round-trip`() {
        val complexValue = mapOf(
            "str" to "test".strVal,
            "num" to 42.numVal,
            "bool" to true.boolVal,
            "list" to listOf(1.numVal, 2.numVal, 3.numVal).listVal,
            "map" to mapOf("nested" to "value".strVal).mapVal,
        ).mapVal

        val id = storage.addNode(mapOf("complex" to complexValue))
        assertEquals(complexValue, storage.getNodeProperties(id)["complex"])
    }

    @Test
    fun `NullVal stored and retrieved correctly`() {
        val id = storage.addNode(mapOf("nullProp" to NullVal, "normal" to "x".strVal))
        val props = storage.getNodeProperties(id)
        assertEquals(NullVal, props["nullProp"])
        assertEquals("x".strVal, props["normal"])
    }
}
