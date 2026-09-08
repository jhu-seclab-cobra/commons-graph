package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Tests for ActiveLayer: the mutable layer of LayeredStorageImpl keyed by global IDs.
 *
 * - `addNode registers empty adjacency and stores columns` -- node insertion
 * - `addEdge records endpoints and links adjacency sets` -- edge insertion
 * - `setNodeProperties with null removes the column entry` -- null delete
 * - `setEdgeProperties overlays and deletes edge columns` -- edge property update
 * - `removeNode cascades to incident edges and columns` -- cascade delete
 * - `removeEdge on unknown id is a no-op` -- absent edge
 * - `clear empties every table including tombstones` -- reset
 */
internal class ActiveLayerTest {
    private lateinit var layer: ActiveLayer

    @BeforeTest
    fun setUp() {
        layer = ActiveLayer()
    }

    @Test
    fun `addNode registers empty adjacency and stores columns`() {
        layer.addNode(7, mapOf("name" to "a".strVal))

        assertTrue(layer.containsNode(7))
        assertEquals(setOf(7), layer.nodeIds)
        assertEquals(emptySet(), layer.outEdges.getValue(7))
        assertEquals(emptySet(), layer.inEdges.getValue(7))
        assertEquals(mapOf("name" to "a".strVal), layer.collectNodeProperties(7))
    }

    @Test
    fun `addEdge records endpoints and links adjacency sets`() {
        layer.addNode(1, emptyMap())
        layer.addNode(2, emptyMap())
        val structure = IStorage.EdgeStructure(1, 2, "t")

        layer.addEdge(10, structure, mapOf("w" to 1.intVal))

        assertTrue(layer.containsEdge(10))
        assertEquals(structure, layer.edgeEndpoints[10])
        assertEquals(setOf(10), layer.outEdges.getValue(1))
        assertEquals(setOf(10), layer.inEdges.getValue(2))
        assertEquals(mapOf("w" to 1.intVal), layer.collectEdgeProperties(10))
    }

    @Test
    fun `setNodeProperties with null removes the column entry`() {
        layer.addNode(1, mapOf("a" to 1.intVal, "b" to 2.intVal))

        layer.setNodeProperties(1, mapOf("a" to null, "c" to 3.intVal))

        assertEquals(mapOf("b" to 2.intVal, "c" to 3.intVal), layer.collectNodeProperties(1))
        assertFalse("a" in layer.nodeColumns)
    }

    @Test
    fun `setEdgeProperties overlays and deletes edge columns`() {
        layer.addNode(1, emptyMap())
        layer.addNode(2, emptyMap())
        layer.addEdge(10, IStorage.EdgeStructure(1, 2, "t"), mapOf("a" to 1.intVal))

        layer.setEdgeProperties(10, mapOf("a" to null, "b" to 2.intVal))

        assertEquals(mapOf("b" to 2.intVal), layer.collectEdgeProperties(10))
    }

    @Test
    fun `removeNode cascades to incident edges and columns`() {
        layer.addNode(1, mapOf("n" to 1.intVal))
        layer.addNode(2, emptyMap())
        layer.addEdge(10, IStorage.EdgeStructure(1, 2, "out"), mapOf("e" to 1.intVal))
        layer.addEdge(11, IStorage.EdgeStructure(2, 1, "in"), emptyMap())

        layer.removeNode(1)

        assertFalse(layer.containsNode(1))
        assertFalse(layer.containsEdge(10))
        assertFalse(layer.containsEdge(11))
        assertEquals(emptySet(), layer.outEdges.getValue(2))
        assertEquals(emptySet(), layer.inEdges.getValue(2))
        assertFalse("n" in layer.nodeColumns)
        assertFalse("e" in layer.edgeColumns)
    }

    @Test
    fun `removeEdge on unknown id is a no-op`() {
        layer.addNode(1, emptyMap())

        layer.removeEdge(99)

        assertEquals(setOf(1), layer.nodeIds)
    }

    @Test
    fun `clear empties every table including tombstones`() {
        layer.addNode(1, mapOf("n" to 1.intVal))
        layer.addNode(2, emptyMap())
        layer.addEdge(10, IStorage.EdgeStructure(1, 2, "t"), mapOf("e" to 1.intVal))
        layer.metaProperties["m"] = 1.intVal
        layer.deletedMetaNames.add("gone")

        layer.clear()

        assertTrue(layer.nodeIds.isEmpty())
        assertTrue(layer.edgeEndpoints.isEmpty())
        assertTrue(layer.nodeColumns.isEmpty())
        assertTrue(layer.edgeColumns.isEmpty())
        assertTrue(layer.metaProperties.isEmpty())
        assertTrue(layer.deletedMetaNames.isEmpty())
    }
}
