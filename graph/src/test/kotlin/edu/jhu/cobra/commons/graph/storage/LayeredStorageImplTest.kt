package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.FrozenLayerModificationException
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayeredStorageImplTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setup() {
        storage = LayeredStorageImpl()
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // region Basic IStorage contract (single layer, no freezing)

    @Test
    fun `test empty storage properties`() {
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test addNode and containsNode`() {
        val node1 = storage.addNode()
        assertTrue(storage.containsNode(node1))
        assertFalse(storage.containsNode(999))
    }

    @Test
    fun `test addNode with properties`() {
        val props = mapOf("name" to "Node1".strVal, "value" to 42.numVal)
        val node1 = storage.addNode(props)
        val retrieved = storage.getNodeProperties(node1)
        assertEquals("Node1", (retrieved["name"] as StrVal).core)
        assertEquals(42, (retrieved["value"] as NumVal).core)
    }

    @Test
    fun `test deleteNode does not cascade edge deletion`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)

        storage.deleteEdge(edge1)
        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node2))
    }

    @Test
    fun `test deleteNode throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(999) }
    }

    @Test
    fun `test addEdge and containsEdge`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test addEdge throws EntityNotExistException for missing src`() {
        val node2 = storage.addNode()
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(999, node2, StorageTestUtils.EDGE_TAG_1)
        }
    }

    @Test
    fun `test addEdge allows multiple edges with same endpoints and type`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        val edge2 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        assertNotEquals(edge1, edge2)
        assertTrue(storage.containsEdge(edge1))
        assertTrue(storage.containsEdge(edge2))
    }

    @Test
    fun `test getIncomingEdges and getOutgoingEdges`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1) // node1 -> node2
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TAG_3) // node1 -> node3

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
        val node1 = storage.addNode(mapOf("a" to "1".strVal, "b" to "2".strVal))
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
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        storage.setMeta("key", "val".strVal)

        storage.clear()
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `test closed storage throws AccessClosedStorageException`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(999) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    // endregion

    // region Freeze and layer

    @Test
    fun `test layerCount starts at 1`() {
        assertEquals(1, storage.layerCount)
    }

    @Test
    fun `test freeze merges into single frozen layer`() {
        storage.addNode()
        storage.freeze()
        assertEquals(2, storage.layerCount)

        storage.addNode()
        storage.freeze()
        assertEquals(2, storage.layerCount)
    }

    @Test
    fun `test frozen layer data is still readable`() {
        val node1 = storage.addNode(mapOf("name" to "Node1".strVal))
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1, mapOf("weight" to 1.0.numVal))
        storage.freeze()

        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsEdge(edge1))
        assertEquals("Node1", (storage.getNodeProperties(node1)["name"] as StrVal).core)
        assertEquals(1.0, (storage.getEdgeProperties(edge1)["weight"] as NumVal).core)
    }

    @Test
    fun `test frozen layer nodeIDs and edgeIDs included`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        storage.freeze()

        // After freeze, frozen layer has node IDs remapped starting from 0.
        // New active layer also starts its counter at 0, so active IDs may
        // shadow frozen IDs. Verify frozen-layer entities remain accessible.
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsEdge(edge1))

        // Adding a node in the active layer — its ID may collide with a frozen ID
        val node3 = storage.addNode()
        assertTrue(storage.containsNode(node3))

        val edgeIds = storage.edgeIDs
        assertEquals(1, edgeIds.size)
        assertTrue(edgeIds.contains(edge1))
    }

    @Test
    fun `test addNode in new layer after freeze`() {
        val node1 = storage.addNode()
        storage.freeze()

        val node3 = storage.addNode()
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node3))
    }

    @Test
    fun `test addEdge with src in frozen layer and dst in active layer`() {
        val node1 = storage.addNode()
        storage.freeze()

        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)

        assertTrue(storage.containsEdge(edge1))
        val incoming = storage.getIncomingEdges(node2)
        assertTrue(incoming.contains(edge1))
        val outgoing = storage.getOutgoingEdges(node1)
        assertTrue(outgoing.contains(edge1))
    }

    @Test
    fun `test addEdge between two frozen layer nodes`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.freeze()

        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test adjacency merges across layers`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        storage.freeze()

        val node3 = storage.addNode()
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TAG_3)

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(edge1))
        assertTrue(outgoing.contains(edge3))
    }

    @Test
    fun `test property overlay across layers`() {
        val node1 = storage.addNode(mapOf("a" to "frozen_a".strVal, "b" to "frozen_b".strVal))
        storage.freeze()

        storage.setNodeProperties(node1, mapOf("a" to "active_a".strVal))

        val props = storage.getNodeProperties(node1)
        assertEquals("active_a", (props["a"] as StrVal).core)
        assertEquals("frozen_b", (props["b"] as StrVal).core)
    }

    @Test
    fun `test edge property overlay across layers`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 =
            storage.addEdge(
                node1,
                node2,
                StorageTestUtils.EDGE_TAG_1,
                mapOf(
                    "x" to "frozen".strVal,
                    "y" to "frozen_y".strVal,
                ),
            )
        storage.freeze()

        storage.setEdgeProperties(edge1, mapOf("x" to "active".strVal))

        val props = storage.getEdgeProperties(edge1)
        assertEquals("active", (props["x"] as StrVal).core)
        assertEquals("frozen_y", (props["y"] as StrVal).core)
    }

    @Test
    fun `test metadata overlay across layers`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freeze()

        storage.setMeta("key", "active".strVal)
        assertEquals("active", (storage.getMeta("key") as StrVal).core)
    }

    @Test
    fun `test metadata falls through to frozen layer`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freeze()

        assertEquals("frozen", (storage.getMeta("key") as StrVal).core)
    }

    // endregion

    // region Active-layer-only deletion

    @Test
    fun `test deleteNode on frozen layer throws FrozenLayerModificationException`() {
        val node1 = storage.addNode()
        storage.freeze()

        assertFailsWith<FrozenLayerModificationException> { storage.deleteNode(node1) }
        assertTrue(storage.containsNode(node1))
    }

    @Test
    fun `test deleteEdge on frozen layer throws FrozenLayerModificationException`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        storage.freeze()

        assertFailsWith<FrozenLayerModificationException> { storage.deleteEdge(edge1) }
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test deleteNode on active layer succeeds after freeze`() {
        storage.freeze()

        // After freeze with empty data, active layer starts fresh.
        // Adding and deleting a node in the active layer succeeds.
        val node = storage.addNode()
        storage.deleteNode(node)
        assertFalse(storage.containsNode(node))
    }

    @Test
    fun `test deleteEdge on active layer succeeds after freeze`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.freeze()

        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
    }

    // endregion

    // region Multi-phase workflow

    @Test
    fun `test multi-phase analysis workflow`() {
        // Phase 1: AST nodes and edge
        val node1 = storage.addNode(mapOf("type" to "ASTNode".strVal, "name" to "n1".strVal))
        val node2 = storage.addNode(mapOf("type" to "ASTNode".strVal, "name" to "n2".strVal))
        storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1, mapOf("type" to "ASTEdge".strVal))
        storage.freeze()

        // Phase 2: Add CFG node and edge (within active layer only)
        val node3 = storage.addNode(mapOf("type" to "CFGNode".strVal, "name" to "n3".strVal))
        // Create edge between two active-layer nodes to avoid cross-layer duplication
        val node4 = storage.addNode(mapOf("type" to "CFGNode".strVal, "name" to "n4".strVal))
        storage.addEdge(node3, node4, StorageTestUtils.EDGE_TAG_2, mapOf("type" to "CFGEdge".strVal))
        storage.freeze()

        // After two freezes, verify by properties
        val allTypes = storage.nodeIDs.map { (storage.getNodeProperties(it)["type"] as StrVal).core }.toSet()
        assertEquals(setOf("ASTNode", "CFGNode"), allTypes)
        assertEquals(4, storage.nodeIDs.size)
        assertEquals(2, storage.edgeIDs.size)

        // Find the node originally named "n1" and annotate it
        val n1StorageId =
            storage.nodeIDs.first {
                (storage.getNodeProperties(it)["name"] as? StrVal)?.core == "n1"
            }
        storage.setNodeProperties(n1StorageId, mapOf("analysisResult" to "safe".strVal))
        val props = storage.getNodeProperties(n1StorageId)
        assertEquals("safe", (props["analysisResult"] as StrVal).core)
        assertEquals("ASTNode", (props["type"] as StrVal).core)
    }

    @Test
    fun `test temporary dummy node in active layer can be deleted`() {
        storage.freeze()

        // After freeze with empty data, active layer starts fresh.
        val node1 = storage.addNode()
        val dummyEntry = storage.addNode()
        val dummyEdge = storage.addEdge(dummyEntry, node1, "cfg_entry")

        assertTrue(storage.containsNode(dummyEntry))

        storage.deleteEdge(dummyEdge)
        storage.deleteNode(dummyEntry)
        assertFalse(storage.containsNode(dummyEntry))
        assertFalse(storage.containsEdge(dummyEdge))
    }

    // endregion

    // region Metadata across layers

    @Test
    fun `test metaNames merges frozen and active layers`() {
        storage.setMeta("frozenKey", "v1".strVal)
        storage.freeze()
        storage.setMeta("activeKey", "v2".strVal)

        val names = storage.metaNames
        assertTrue(names.contains("frozenKey"))
        assertTrue(names.contains("activeKey"))
    }

    @Test
    fun `test getMeta searches multiple frozen layers`() {
        storage.setMeta("layer1", "v1".strVal)
        storage.freeze()
        storage.setMeta("layer2", "v2".strVal)
        storage.freeze()

        assertEquals("v1", (storage.getMeta("layer1") as StrVal).core)
        assertEquals("v2", (storage.getMeta("layer2") as StrVal).core)
        assertNull(storage.getMeta("nonexistent"))
    }

    // endregion

    // region Edge property overlay across layers

    @Test
    fun `test setEdgeProperties on frozen layer edge creates active layer copy`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1, mapOf("x" to "frozen".strVal))
        storage.freeze()

        storage.setEdgeProperties(edge1, mapOf("x" to "active".strVal))
        assertEquals("active", (storage.getEdgeProperties(edge1)["x"] as StrVal).core)
    }

    @Test
    fun `test getEdgeProperties merges across frozen and active layers`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 =
            storage.addEdge(
                node1,
                node2,
                StorageTestUtils.EDGE_TAG_1,
                mapOf("a" to "frozen_a".strVal),
            )
        storage.freeze()

        storage.setEdgeProperties(edge1, mapOf("b" to "active_b".strVal))
        val props = storage.getEdgeProperties(edge1)
        assertEquals("frozen_a", (props["a"] as StrVal).core)
        assertEquals("active_b", (props["b"] as StrVal).core)
    }

    @Test
    fun `test getOutgoingEdges merges across frozen and active layers`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1)
        storage.freeze()

        val node3 = storage.addNode()
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TAG_3) // node1 -> node3

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(edge1))
        assertTrue(outgoing.contains(edge3))
    }

    @Test
    fun `test getIncomingEdges merges across frozen and active layers`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TAG_1) // node1 -> node2
        storage.freeze()

        val node3 = storage.addNode()
        val edge = storage.addEdge(node3, node2, "extra")

        val incoming = storage.getIncomingEdges(node2)
        assertEquals(2, incoming.size)
        assertTrue(incoming.contains(edge1))
        assertTrue(incoming.contains(edge))
    }

    // endregion

    // region LazyMergedMap view coverage

    @Test
    fun `test property overlay map size counts deduplicated keys`() {
        val node1 = storage.addNode(mapOf("a" to "frozen_a".strVal, "b" to "frozen_b".strVal))
        storage.freeze()
        storage.setNodeProperties(node1, mapOf("a" to "active_a".strVal, "c" to "active_c".strVal))

        val props = storage.getNodeProperties(node1)

        assertEquals(3, props.size)
    }

    @Test
    fun `test property overlay map containsKey checks both layers`() {
        val node1 = storage.addNode(mapOf("frozen_only" to "v".strVal))
        storage.freeze()
        storage.setNodeProperties(node1, mapOf("active_only" to "v".strVal))

        val props = storage.getNodeProperties(node1)

        assertTrue(props.containsKey("frozen_only"))
        assertTrue(props.containsKey("active_only"))
        assertFalse(props.containsKey("absent"))
    }

    @Test
    fun `test property overlay map get prefers active over frozen`() {
        val node1 = storage.addNode(mapOf("key" to "frozen".strVal))
        storage.freeze()
        storage.setNodeProperties(node1, mapOf("key" to "active".strVal))

        val props = storage.getNodeProperties(node1)

        assertEquals("active", (props["key"] as StrVal).core)
    }

    @Test
    fun `test property overlay map entries merges both layers`() {
        val node1 = storage.addNode(mapOf("a" to "1".strVal, "b" to "2".strVal))
        storage.freeze()
        storage.setNodeProperties(node1, mapOf("b" to "3".strVal, "c" to "4".strVal))

        val entries = storage.getNodeProperties(node1).entries

        val keys = entries.map { it.key }.toSet()
        assertEquals(setOf("a", "b", "c"), keys)
        assertEquals("3", (entries.first { it.key == "b" }.value as StrVal).core)
    }

    @Test
    fun `test property overlay map isEmpty returns false when either layer has data`() {
        val node1 = storage.addNode(mapOf("a" to "1".strVal))
        storage.freeze()

        val props = storage.getNodeProperties(node1)
        assertFalse(props.isEmpty())
    }

    @Test
    fun `test edge property overlay map size and iteration`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge =
            storage.addEdge(
                node1,
                node2,
                "rel",
                mapOf("x" to "frozen".strVal, "y" to "frozen_y".strVal),
            )
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("x" to "active".strVal, "z" to "active_z".strVal))

        val props = storage.getEdgeProperties(edge)

        assertEquals(3, props.size)
        val keys = props.entries.map { it.key }.toSet()
        assertEquals(setOf("x", "y", "z"), keys)
    }

    // endregion

    // region UnionSet view coverage

    @Test
    fun `test nodeIDs union set size is correct`() {
        val n1 = storage.addNode()
        storage.freeze()
        val n2 = storage.addNode()

        val ids = storage.nodeIDs

        assertEquals(2, ids.size)
        assertTrue(ids.contains(n1))
        assertTrue(ids.contains(n2))
    }

    @Test
    fun `test nodeIDs union set iteration yields all nodes`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.freeze()
        val n3 = storage.addNode()

        val collected = storage.nodeIDs.toList()

        assertEquals(3, collected.size)
        assertTrue(n1 in collected)
        assertTrue(n2 in collected)
        assertTrue(n3 in collected)
    }

    @Test
    fun `test edgeIDs union set size and iteration`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "rel1")
        storage.freeze()
        val n3 = storage.addNode()
        val e2 = storage.addEdge(n1, n3, "rel2")

        val ids = storage.edgeIDs

        assertEquals(2, ids.size)
        assertTrue(ids.contains(e1))
        assertTrue(ids.contains(e2))
        assertEquals(2, ids.toList().size)
    }

    @Test
    fun `test adjacency union set size and contains`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "rel1")
        storage.freeze()
        val n3 = storage.addNode()
        val e2 = storage.addEdge(n1, n3, "rel2")

        val outgoing = storage.getOutgoingEdges(n1)

        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(e1))
        assertTrue(outgoing.contains(e2))
        assertFalse(outgoing.isEmpty())
    }

    @Test
    fun `test metaNames union set across layers`() {
        storage.setMeta("frozenKey", "v1".strVal)
        storage.freeze()
        storage.setMeta("activeKey", "v2".strVal)

        val names = storage.metaNames

        assertEquals(2, names.size)
        assertTrue(names.contains("frozenKey"))
        assertTrue(names.contains("activeKey"))
        assertFalse(names.isEmpty())
        assertEquals(2, names.toList().size)
    }

    // endregion

    // region Single-property access across layers

    @Test
    fun `test getNodeProperty returns active layer value over frozen`() {
        val node = storage.addNode(mapOf("key" to "frozen".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("key" to "active".strVal))

        assertEquals("active", (storage.getNodeProperty(node, "key") as StrVal).core)
    }

    @Test
    fun `test getNodeProperty falls through to frozen layer`() {
        val node = storage.addNode(mapOf("key" to "frozen".strVal))
        storage.freeze()

        assertEquals("frozen", (storage.getNodeProperty(node, "key") as StrVal).core)
    }

    @Test
    fun `test getNodeProperty returns null for absent property`() {
        val node = storage.addNode()
        storage.freeze()

        assertNull(storage.getNodeProperty(node, "absent"))
    }

    @Test
    fun `test getNodeProperty throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(999, "key") }
    }

    @Test
    fun `test getEdgeProperty returns active layer value over frozen`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("key" to "frozen".strVal))
        storage.freeze()
        storage.setEdgeProperties(e, mapOf("key" to "active".strVal))

        assertEquals("active", (storage.getEdgeProperty(e, "key") as StrVal).core)
    }

    @Test
    fun `test getEdgeProperty falls through to frozen layer`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("key" to "frozen".strVal))
        storage.freeze()

        assertEquals("frozen", (storage.getEdgeProperty(e, "key") as StrVal).core)
    }

    @Test
    fun `test getEdgeProperty returns null for absent property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.freeze()

        assertNull(storage.getEdgeProperty(e, "absent"))
    }

    @Test
    fun `test getEdgeProperty throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperty(999, "key") }
    }

    // endregion

    // region TransferTo

    @Test
    fun `test transferTo copies all data across layers`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        storage.addEdge(n1, n2, "rel", mapOf("w" to 1.numVal))
        storage.setMeta("version", "1.0".strVal)
        storage.freeze()
        val n3 = storage.addNode(mapOf("name" to "C".strVal))
        storage.addEdge(n1, n3, "rel2")

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(3, target.nodeIDs.size)
        assertEquals(2, target.edgeIDs.size)
        assertEquals("1.0", (target.getMeta("version") as StrVal).core)
        target.close()
    }

    // endregion

    // region Error paths

    @Test
    fun `test deleteNode throws EntityNotExistException for non-existent node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(999) }
    }

    @Test
    fun `test deleteEdge throws EntityNotExistException for non-existent edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(999) }
    }

    @Test
    fun `test setNodeProperties throws EntityNotExistException for non-existent node`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(999, mapOf("a" to "v".strVal))
        }
    }

    @Test
    fun `test setEdgeProperties throws EntityNotExistException for non-existent edge`() {
        assertFailsWith<EntityNotExistException> {
            storage.setEdgeProperties(999, mapOf("a" to "v".strVal))
        }
    }

    @Test
    fun `test getNodeProperties throws EntityNotExistException for non-existent node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(999) }
    }

    @Test
    fun `test getEdgeProperties throws EntityNotExistException for non-existent edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(999) }
    }

    @Test
    fun `test getIncomingEdges throws EntityNotExistException for non-existent node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(999) }
    }

    @Test
    fun `test getOutgoingEdges throws EntityNotExistException for non-existent node`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(999) }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when dst missing`() {
        val node1 = storage.addNode()
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(node1, 999, StorageTestUtils.EDGE_TAG_1)
        }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when src missing`() {
        val node2 = storage.addNode()
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(999, node2, StorageTestUtils.EDGE_TAG_1)
        }
    }

    // endregion

    // region Close and clear

    @Test
    fun `test close is idempotent`() {
        storage.close()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
    }

    @Test
    fun `test clear removes frozen layers and active layer`() {
        storage.addNode()
        storage.freeze()
        storage.addNode()

        storage.clear()
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertEquals(1, storage.layerCount)
    }

    // endregion
}
