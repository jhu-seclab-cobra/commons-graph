package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * Black-box tests for PhasedStorageImpl focusing on public API contracts.
 * Tests cover IStorage contract, layering semantics,
 * and active-layer-only deletion constraint.
 */
class PhasedStorageImplBlackBoxTest {

    private lateinit var storage: PhasedStorageImpl

    private val node1 = StorageTestUtils.node1
    private val node2 = StorageTestUtils.node2
    private val node3 = StorageTestUtils.node3
    private val edge1 = StorageTestUtils.edge1
    private val edge2 = StorageTestUtils.edge2
    private val edge3 = StorageTestUtils.edge3

    @BeforeTest
    fun setup() {
        storage = PhasedStorageImpl()
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // BASIC IStorage CONTRACT (single layer, no freezing)
    // ============================================================================

    @Test
    fun `test empty storage properties`() {
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test addNode and containsNode`() {
        storage.addNode(node1)
        assertTrue(storage.containsNode(node1))
        assertFalse(storage.containsNode(node2))
    }

    @Test
    fun `test addNode with properties`() {
        val props = mapOf("name" to "Node1".strVal, "value" to 42.numVal)
        storage.addNode(node1, props)

        val retrieved = storage.getNodeProperties(node1)
        assertEquals("Node1", (retrieved["name"] as StrVal).core)
        assertEquals(42, (retrieved["value"] as NumVal).core)
    }

    @Test
    fun `test addNode throws EntityAlreadyExistException`() {
        storage.addNode(node1)
        assertFailsWith<EntityAlreadyExistException> { storage.addNode(node1) }
    }

    @Test
    fun `test deleteNode removes node and connected edges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node2))
    }

    @Test
    fun `test deleteNode throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(node1) }
    }

    @Test
    fun `test addEdge and containsEdge`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test addEdge throws EntityNotExistException for missing src`() {
        storage.addNode(node2)
        assertFailsWith<EntityNotExistException> { storage.addEdge(edge1) }
    }

    @Test
    fun `test addEdge throws EntityAlreadyExistException`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        assertFailsWith<EntityAlreadyExistException> { storage.addEdge(edge1) }
    }

    @Test
    fun `test getIncomingEdges and getOutgoingEdges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge3) // node1 -> node3

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(edge1))
        assertTrue(outgoing.contains(edge3))

        val incoming = storage.getIncomingEdges(node2)
        assertEquals(1, incoming.size)
        assertTrue(incoming.contains(edge1))
    }

    @Test
    fun `test setNodeProperties updates and deletes`() {
        storage.addNode(node1, mapOf("a" to "1".strVal, "b" to "2".strVal))
        storage.setNodeProperties(node1, mapOf("a" to "updated".strVal, "b" to null))

        val props = storage.getNodeProperties(node1)
        assertEquals("updated", (props["a"] as StrVal).core)
        assertFalse(props.containsKey("b"))
    }

    @Test
    fun `test metadata operations`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)

        storage.setMeta("version", null)
        assertNull(storage.getMeta("version"))
    }

    @Test
    fun `test clear removes all data`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.setMeta("key", "val".strVal)

        assertTrue(storage.clear())
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `test closed storage throws AccessClosedStorageException`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(node1) }
    }

    // ============================================================================
    // FREEZE AND LAYER TESTS
    // ============================================================================

    @Test
    fun `test layerCount starts at 1`() {
        assertEquals(1, storage.layerCount)
    }

    @Test
    fun `test freezeAndPushLayer increments layerCount`() {
        storage.addNode(node1)
        storage.freezeAndPushLayer()
        assertEquals(2, storage.layerCount)

        storage.addNode(node2)
        storage.freezeAndPushLayer()
        assertEquals(3, storage.layerCount)
    }

    @Test
    fun `test frozen layer data is still readable`() {
        storage.addNode(node1, mapOf("name" to "Node1".strVal))
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("weight" to 1.0.numVal))
        storage.freezeAndPushLayer()

        // Data from frozen layer should still be accessible
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsEdge(edge1))
        assertEquals("Node1", (storage.getNodeProperties(node1)["name"] as StrVal).core)
        assertEquals(1.0, (storage.getEdgeProperties(edge1)["weight"] as NumVal).core)
    }

    @Test
    fun `test frozen layer nodeIDs and edgeIDs included`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.freezeAndPushLayer()

        storage.addNode(node3)

        val nodeIds = storage.nodeIDs
        assertEquals(3, nodeIds.size)
        assertTrue(nodeIds.containsAll(listOf(node1, node2, node3)))

        val edgeIds = storage.edgeIDs
        assertEquals(1, edgeIds.size)
        assertTrue(edgeIds.contains(edge1))
    }

    @Test
    fun `test addNode in new layer after freeze`() {
        storage.addNode(node1)
        storage.freezeAndPushLayer()

        storage.addNode(node3)
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node3))
    }

    @Test
    fun `test addNode throws if node already in frozen layer`() {
        storage.addNode(node1)
        storage.freezeAndPushLayer()

        assertFailsWith<EntityAlreadyExistException> { storage.addNode(node1) }
    }

    @Test
    fun `test addEdge with src in frozen layer and dst in active layer`() {
        storage.addNode(node1)
        storage.freezeAndPushLayer()

        storage.addNode(node2)
        // edge1: node1 -> node2, node1 is frozen, node2 is active
        storage.addEdge(edge1)

        assertTrue(storage.containsEdge(edge1))
        val incoming = storage.getIncomingEdges(node2)
        assertTrue(incoming.contains(edge1))
        val outgoing = storage.getOutgoingEdges(node1)
        assertTrue(outgoing.contains(edge1))
    }

    @Test
    fun `test addEdge between two frozen layer nodes`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.freezeAndPushLayer()

        storage.addEdge(edge1)
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test adjacency merges across layers`() {
        // Freeze layer with edge1: node1 -> node2
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.freezeAndPushLayer()

        // Active layer with edge3: node1 -> node3
        storage.addNode(node3)
        storage.addEdge(edge3)

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(edge1))
        assertTrue(outgoing.contains(edge3))
    }

    @Test
    fun `test property overlay across layers`() {
        storage.addNode(node1, mapOf("a" to "frozen_a".strVal, "b" to "frozen_b".strVal))
        storage.freezeAndPushLayer()

        // Override property "a" in active layer, leave "b" from frozen
        storage.setNodeProperties(node1, mapOf("a" to "active_a".strVal))

        val props = storage.getNodeProperties(node1)
        assertEquals("active_a", (props["a"] as StrVal).core)
        assertEquals("frozen_b", (props["b"] as StrVal).core)
    }

    @Test
    fun `test edge property overlay across layers`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("x" to "frozen".strVal, "y" to "frozen_y".strVal))
        storage.freezeAndPushLayer()

        storage.setEdgeProperties(edge1, mapOf("x" to "active".strVal))

        val props = storage.getEdgeProperties(edge1)
        assertEquals("active", (props["x"] as StrVal).core)
        assertEquals("frozen_y", (props["y"] as StrVal).core)
    }

    @Test
    fun `test metadata overlay across layers`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freezeAndPushLayer()

        // Active layer meta overrides frozen
        storage.setMeta("key", "active".strVal)
        assertEquals("active", (storage.getMeta("key") as StrVal).core)
    }

    @Test
    fun `test metadata falls through to frozen layer`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freezeAndPushLayer()

        assertEquals("frozen", (storage.getMeta("key") as StrVal).core)
    }

    // ============================================================================
    // ACTIVE-LAYER-ONLY DELETION
    // ============================================================================

    @Test
    fun `test deleteNode on frozen layer throws FrozenLayerModificationException`() {
        storage.addNode(node1)
        storage.freezeAndPushLayer()

        assertFailsWith<FrozenLayerModificationException> { storage.deleteNode(node1) }
        assertTrue(storage.containsNode(node1))
    }

    @Test
    fun `test deleteEdge on frozen layer throws FrozenLayerModificationException`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.freezeAndPushLayer()

        assertFailsWith<FrozenLayerModificationException> { storage.deleteEdge(edge1) }
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test deleteNode on active layer succeeds after freeze`() {
        storage.addNode(node1)
        storage.freezeAndPushLayer()

        storage.addNode(node3)
        storage.deleteNode(node3)
        assertFalse(storage.containsNode(node3))
    }

    @Test
    fun `test deleteEdge on active layer succeeds after freeze`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.freezeAndPushLayer()

        storage.addEdge(edge1)
        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
    }

    // ============================================================================
    // FREEZE (FULL READ-ONLY)
    // ============================================================================

    @Test
    fun `test freeze makes storage read-only`() {
        storage.addNode(node1)
        assertFalse(storage.isFrozen)

        storage.freeze()
        assertTrue(storage.isFrozen)

        // Reads still work
        assertTrue(storage.containsNode(node1))

        // Writes throw
        assertFailsWith<StorageFrozenException> { storage.addNode(node2) }
        assertFailsWith<StorageFrozenException> { storage.deleteNode(node1) }
        assertFailsWith<StorageFrozenException> { storage.setNodeProperties(node1, mapOf()) }
        assertFailsWith<StorageFrozenException> { storage.setMeta("k", "v".strVal) }
        assertFailsWith<StorageFrozenException> { storage.clear() }
        assertFailsWith<StorageFrozenException> { storage.freezeAndPushLayer() }
    }

    @Test
    fun `test freeze is idempotent`() {
        storage.freeze()
        storage.freeze()
        assertTrue(storage.isFrozen)
    }

    // ============================================================================
    // COMPACT LAYERS
    // ============================================================================

    @Test
    fun `test compactLayers merges frozen layers`() {
        storage.addNode(node1, mapOf("phase" to "1".strVal))
        storage.freezeAndPushLayer()

        storage.addNode(node2, mapOf("phase" to "2".strVal))
        storage.freezeAndPushLayer()

        assertEquals(3, storage.layerCount) // 2 frozen + 1 active

        storage.compactLayers(2)
        assertEquals(2, storage.layerCount) // 1 compacted frozen + 1 active

        // Data from both frozen layers still accessible
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertEquals("1", (storage.getNodeProperties(node1)["phase"] as StrVal).core)
        assertEquals("2", (storage.getNodeProperties(node2)["phase"] as StrVal).core)
    }

    @Test
    fun `test compactLayers throws on invalid topN`() {
        storage.addNode(node1)
        storage.freezeAndPushLayer()

        assertFailsWith<IllegalArgumentException> { storage.compactLayers(0) }
        assertFailsWith<IllegalArgumentException> { storage.compactLayers(2) }
    }

    // ============================================================================
    // MULTI-PHASE WORKFLOW
    // ============================================================================

    @Test
    fun `test multi-phase analysis workflow`() {
        // Phase 1: build AST
        storage.addNode(node1, mapOf("type" to "ASTNode".strVal))
        storage.addNode(node2, mapOf("type" to "ASTNode".strVal))
        storage.addEdge(edge1, mapOf("type" to "ASTEdge".strVal))
        storage.freezeAndPushLayer()

        // Phase 2: build CFG
        storage.addNode(node3, mapOf("type" to "CFGNode".strVal))
        storage.addEdge(edge2, mapOf("type" to "CFGEdge".strVal))
        storage.freezeAndPushLayer()

        // Phase 3: analysis — all data readable
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsNode(node3))
        assertTrue(storage.containsEdge(edge1))
        assertTrue(storage.containsEdge(edge2))

        assertEquals(3, storage.nodeIDs.size)
        assertEquals(2, storage.edgeIDs.size)

        // Can still add analysis results to active layer
        storage.setNodeProperties(node1, mapOf("analysisResult" to "safe".strVal))
        val props = storage.getNodeProperties(node1)
        assertEquals("safe", (props["analysisResult"] as StrVal).core)
        assertEquals("ASTNode", (props["type"] as StrVal).core)
    }

    @Test
    fun `test temporary dummy node in active layer can be deleted`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.freezeAndPushLayer()

        // Create temporary dummy node for CFG construction
        val dummyEntry = NodeID("dummy_entry")
        val dummyEdge = EdgeID(dummyEntry, node1, "cfg_entry")
        storage.addNode(dummyEntry)
        storage.addEdge(dummyEdge)

        assertTrue(storage.containsNode(dummyEntry))

        // Clean up dummy before freeze
        storage.deleteEdge(dummyEdge)
        storage.deleteNode(dummyEntry)
        assertFalse(storage.containsNode(dummyEntry))
        assertFalse(storage.containsEdge(dummyEdge))
    }

    // ============================================================================
    // CLEAR WITH FROZEN LAYERS
    // ============================================================================

    @Test
    fun `test clear removes frozen layers and active layer`() {
        storage.addNode(node1)
        storage.freezeAndPushLayer()
        storage.addNode(node2)

        assertTrue(storage.clear())
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertEquals(1, storage.layerCount)
    }
}
