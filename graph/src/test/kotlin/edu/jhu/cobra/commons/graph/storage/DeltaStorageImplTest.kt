package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class DeltaStorageImplTest {
    private lateinit var base: NativeStorageImpl
    private lateinit var storage: DeltaStorageImpl

    private val node1 = StorageTestUtils.node1
    private val node2 = StorageTestUtils.node2
    private val node3 = StorageTestUtils.node3
    private val edge1 = StorageTestUtils.edge1
    private val edge2 = StorageTestUtils.edge2
    private val edge3 = StorageTestUtils.edge3

    @BeforeTest
    fun setup() {
        base = NativeStorageImpl()
        storage = DeltaStorageImpl(base)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        base.close()
    }

    // region Properties and statistics

    @Test
    fun `test empty storage properties`() {
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test nodeIDs merges base and present`() {
        base.addNode(node1)
        storage.addNode(node2)

        val ids = storage.nodeIDs
        assertEquals(2, ids.size)
        assertTrue(ids.contains(node1))
        assertTrue(ids.contains(node2))
    }

    @Test
    fun `test edgeIDs merges base and present`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addNode(node3)
        base.addEdge(edge1)
        storage.addEdge(edge2)

        val ids = storage.edgeIDs
        assertEquals(2, ids.size)
        assertTrue(ids.contains(edge1))
        assertTrue(ids.contains(edge2))
    }

    @Test
    fun `test nodeIDs excludes deleted nodes`() {
        base.addNode(node1)
        base.addNode(node2)
        storage.deleteNode(node1)

        val ids = storage.nodeIDs
        assertEquals(1, ids.size)
        assertFalse(ids.contains(node1))
        assertTrue(ids.contains(node2))
    }

    @Test
    fun `test edgeIDs excludes deleted edges`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1)
        storage.deleteEdge(edge1)

        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test nodeIDs throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
    }

    @Test
    fun `test edgeIDs throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
    }

    // endregion

    // region Node operations

    @Test
    fun `test containsNode for base node`() {
        base.addNode(node1)
        assertTrue(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode for present node`() {
        storage.addNode(node1)
        assertTrue(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode returns false for deleted base node`() {
        base.addNode(node1)
        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode returns false for non-existent node`() {
        assertFalse(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
    }

    @Test
    fun `test addNode to present layer`() {
        storage.addNode(node1, mapOf("key" to "val".strVal))

        assertTrue(storage.containsNode(node1))
        assertEquals("val", (storage.getNodeProperties(node1)["key"] as StrVal).core)
    }

    @Test
    fun `test addNode throws EntityAlreadyExistException for existing node`() {
        storage.addNode(node1)
        assertFailsWith<EntityAlreadyExistException> { storage.addNode(node1) }
    }

    @Test
    fun `test addNode throws EntityAlreadyExistException for base node`() {
        base.addNode(node1)
        assertFailsWith<EntityAlreadyExistException> { storage.addNode(node1) }
    }

    @Test
    fun `test addNode re-adds previously deleted base node`() {
        base.addNode(node1, mapOf("key" to "base".strVal))
        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))

        storage.addNode(node1)
        assertTrue(storage.containsNode(node1))
    }

    @Test
    fun `test addNode throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.addNode(node1) }
    }

    @Test
    fun `test getNodeProperties from base only`() {
        base.addNode(node1, mapOf("a" to "1".strVal))

        val props = storage.getNodeProperties(node1)
        assertEquals("1", (props["a"] as StrVal).core)
    }

    @Test
    fun `test getNodeProperties from present only`() {
        storage.addNode(node1, mapOf("b" to "2".strVal))

        val props = storage.getNodeProperties(node1)
        assertEquals("2", (props["b"] as StrVal).core)
    }

    @Test
    fun `test getNodeProperties merges base and present with overlay`() {
        base.addNode(node1, mapOf("a" to "base_a".strVal, "b" to "base_b".strVal))
        storage.setNodeProperties(node1, mapOf("a" to "present_a".strVal))

        val props = storage.getNodeProperties(node1)
        assertEquals("present_a", (props["a"] as StrVal).core)
        assertEquals("base_b", (props["b"] as StrVal).core)
    }

    @Test
    fun `test getNodeProperties filters deleted sentinel values`() {
        base.addNode(node1, mapOf("a" to "base_a".strVal, "b" to "base_b".strVal))
        storage.setNodeProperties(node1, mapOf("a" to null))

        val props = storage.getNodeProperties(node1)
        assertFalse(props.containsKey("a"))
        assertEquals("base_b", (props["b"] as StrVal).core)
    }

    @Test
    fun `test getNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(node1) }
    }

    @Test
    fun `test setNodeProperties on base-only node creates present overlay`() {
        base.addNode(node1, mapOf("a" to "old".strVal))
        storage.setNodeProperties(node1, mapOf("a" to "new".strVal))

        assertEquals("new", (storage.getNodeProperties(node1)["a"] as StrVal).core)
    }

    @Test
    fun `test setNodeProperties on present node updates in place`() {
        storage.addNode(node1, mapOf("a" to "v1".strVal))
        storage.setNodeProperties(node1, mapOf("a" to "v2".strVal))

        assertEquals("v2", (storage.getNodeProperties(node1)["a"] as StrVal).core)
    }

    @Test
    fun `test setNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(node1, mapOf("a" to "v".strVal))
        }
    }

    @Test
    fun `test deleteNode from present layer`() {
        storage.addNode(node1)
        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
    }

    @Test
    fun `test deleteNode from base layer marks as deleted`() {
        base.addNode(node1)
        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertTrue(base.containsNode(node1))
    }

    @Test
    fun `test deleteNode removes associated base edges`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addNode(node3)
        base.addEdge(edge1) // node1 -> node2
        base.addEdge(edge3) // node1 -> node3

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
        assertFalse(storage.containsEdge(edge3))
    }

    @Test
    fun `test deleteNode throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(node1) }
    }

    // endregion

    // region Edge operations

    @Test
    fun `test containsEdge for base edge`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1)
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test containsEdge for present edge`() {
        base.addNode(node1)
        base.addNode(node2)
        storage.addEdge(edge1)
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test containsEdge returns false for deleted edge`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1)
        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
    }

    @Test
    fun `test containsEdge throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(edge1) }
    }

    @Test
    fun `test addEdge to present layer`() {
        base.addNode(node1)
        base.addNode(node2)
        storage.addEdge(edge1, mapOf("w" to 1.0.numVal))

        assertTrue(storage.containsEdge(edge1))
        assertEquals(1.0, (storage.getEdgeProperties(edge1)["w"] as NumVal).core)
    }

    @Test
    fun `test addEdge throws EntityNotExistException for missing src`() {
        base.addNode(node2)
        assertFailsWith<EntityNotExistException> { storage.addEdge(edge1) }
    }

    @Test
    fun `test addEdge throws EntityNotExistException for missing dst`() {
        base.addNode(node1)
        assertFailsWith<EntityNotExistException> { storage.addEdge(edge1) }
    }

    @Test
    fun `test addEdge throws EntityAlreadyExistException for existing edge`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1)
        assertFailsWith<EntityAlreadyExistException> { storage.addEdge(edge1) }
    }

    @Test
    fun `test addEdge re-adds previously deleted base edge`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1)
        storage.deleteEdge(edge1)

        storage.addEdge(edge1)
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test addEdge creates present node stubs for base-only nodes`() {
        base.addNode(node1)
        base.addNode(node2)
        storage.addEdge(edge1)
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test getEdgeProperties from base only`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1, mapOf("x" to "base".strVal))

        assertEquals("base", (storage.getEdgeProperties(edge1)["x"] as StrVal).core)
    }

    @Test
    fun `test getEdgeProperties from present only`() {
        base.addNode(node1)
        base.addNode(node2)
        storage.addEdge(edge1, mapOf("x" to "present".strVal))

        assertEquals("present", (storage.getEdgeProperties(edge1)["x"] as StrVal).core)
    }

    @Test
    fun `test getEdgeProperties merges base and present`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1, mapOf("a" to "base_a".strVal, "b" to "base_b".strVal))
        storage.setEdgeProperties(edge1, mapOf("a" to "present_a".strVal))

        val props = storage.getEdgeProperties(edge1)
        assertEquals("present_a", (props["a"] as StrVal).core)
        assertEquals("base_b", (props["b"] as StrVal).core)
    }

    @Test
    fun `test getEdgeProperties filters deleted sentinel values`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1, mapOf("a" to "base_a".strVal, "b" to "base_b".strVal))
        storage.setEdgeProperties(edge1, mapOf("a" to null))

        val props = storage.getEdgeProperties(edge1)
        assertFalse(props.containsKey("a"))
        assertEquals("base_b", (props["b"] as StrVal).core)
    }

    @Test
    fun `test getEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(edge1) }
    }

    @Test
    fun `test setEdgeProperties on base-only edge creates present overlay`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1, mapOf("a" to "old".strVal))
        storage.setEdgeProperties(edge1, mapOf("a" to "new".strVal))

        assertEquals("new", (storage.getEdgeProperties(edge1)["a"] as StrVal).core)
    }

    @Test
    fun `test setEdgeProperties on present edge updates in place`() {
        base.addNode(node1)
        base.addNode(node2)
        storage.addEdge(edge1, mapOf("a" to "v1".strVal))
        storage.setEdgeProperties(edge1, mapOf("a" to "v2".strVal))

        assertEquals("v2", (storage.getEdgeProperties(edge1)["a"] as StrVal).core)
    }

    @Test
    fun `test setEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> {
            storage.setEdgeProperties(edge1, mapOf("a" to "v".strVal))
        }
    }

    @Test
    fun `test deleteEdge from present layer`() {
        base.addNode(node1)
        base.addNode(node2)
        storage.addEdge(edge1)
        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
    }

    @Test
    fun `test deleteEdge from base layer marks as deleted`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1)
        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
        assertTrue(base.containsEdge(edge1))
    }

    @Test
    fun `test deleteEdge throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(edge1) }
    }

    // endregion

    // region Graph structure queries

    @Test
    fun `test getIncomingEdges merges base and present`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addNode(node3)
        base.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3

        val incoming3 = storage.getIncomingEdges(node3)
        assertEquals(1, incoming3.size)
        assertTrue(incoming3.contains(edge2))

        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))
    }

    @Test
    fun `test getOutgoingEdges merges base and present`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addNode(node3)
        base.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge3) // node1 -> node3

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(edge1))
        assertTrue(outgoing.contains(edge3))
    }

    @Test
    fun `test getIncomingEdges excludes deleted edges`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addNode(node3)
        base.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3
        storage.deleteEdge(edge1)

        val incoming2 = storage.getIncomingEdges(node2)
        assertTrue(incoming2.isEmpty())
    }

    @Test
    fun `test getOutgoingEdges excludes deleted edges`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addNode(node3)
        base.addEdge(edge1) // node1 -> node2
        base.addEdge(edge3) // node1 -> node3
        storage.deleteEdge(edge1)

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(1, outgoing.size)
        assertTrue(outgoing.contains(edge3))
    }

    @Test
    fun `test getIncomingEdges for node only in present`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        val incoming = storage.getIncomingEdges(node2)
        assertEquals(1, incoming.size)
        assertTrue(incoming.contains(edge1))
    }

    @Test
    fun `test getOutgoingEdges returns empty when no deleted edges holder`() {
        base.addNode(node1)
        base.addNode(node2)
        base.addEdge(edge1)

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(1, outgoing.size)
        assertTrue(outgoing.contains(edge1))
    }

    // endregion

    // region Metadata operations

    @Test
    fun `test metaNames merges base and present`() {
        base.setMeta("baseKey", "v1".strVal)
        storage.setMeta("presentKey", "v2".strVal)

        val names = storage.metaNames
        assertTrue(names.contains("baseKey"))
        assertTrue(names.contains("presentKey"))
    }

    @Test
    fun `test metaNames throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
    }

    @Test
    fun `test getMeta returns present value over base`() {
        base.setMeta("key", "base".strVal)
        storage.setMeta("key", "present".strVal)

        assertEquals("present", (storage.getMeta("key") as StrVal).core)
    }

    @Test
    fun `test getMeta falls through to base`() {
        base.setMeta("key", "base".strVal)

        assertEquals("base", (storage.getMeta("key") as StrVal).core)
    }

    @Test
    fun `test getMeta returns null for non-existent key`() {
        assertNull(storage.getMeta("nope"))
    }

    @Test
    fun `test getMeta throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("key") }
    }

    @Test
    fun `test setMeta writes to present layer`() {
        storage.setMeta("key", "val".strVal)
        assertEquals("val", (storage.getMeta("key") as StrVal).core)
    }

    @Test
    fun `test setMeta throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("k", "v".strVal) }
    }

    // endregion

    // region Utility operations

    @Test
    fun `test clear clears present layer`() {
        storage.addNode(node1)
        storage.setMeta("k", "v".strVal)

        assertTrue(storage.clear())
    }

    @Test
    fun `test close sets closed state`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
    }

    @Test
    fun `test close does not close base layer`() {
        storage.close()
        assertTrue(base.nodeIDs.isEmpty())
    }

    // endregion

    // region Integration

    @Test
    fun `test complex delta workflow`() {
        base.addNode(node1, mapOf("name" to "N1".strVal))
        base.addNode(node2, mapOf("name" to "N2".strVal))
        base.addEdge(edge1, mapOf("weight" to 1.0.numVal))

        storage.addNode(node3, mapOf("name" to "N3".strVal))
        storage.addEdge(edge2, mapOf("weight" to 2.0.numVal))

        assertEquals(3, storage.nodeIDs.size)
        assertEquals(2, storage.edgeIDs.size)

        storage.setNodeProperties(node1, mapOf("name" to "Updated".strVal))
        assertEquals("Updated", (storage.getNodeProperties(node1)["name"] as StrVal).core)

        storage.deleteEdge(edge1)
        assertEquals(1, storage.edgeIDs.size)
        assertFalse(storage.containsEdge(edge1))

        storage.deleteNode(node2)
        assertEquals(2, storage.nodeIDs.size)
    }

    // endregion
}
