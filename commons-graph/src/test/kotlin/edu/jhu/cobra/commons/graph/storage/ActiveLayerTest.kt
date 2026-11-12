package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Tests for ActiveLayer: the mutable layer of LayeredStorageImpl keyed by global IDs.
 *
 * - `addNode registers empty adjacency and stores the row` -- node insertion
 * - `addEdge records endpoints and links adjacency sets` -- edge insertion
 * - `setNodeProperties with null removes the name from the row` -- null delete
 * - `setEdgeProperties overlays and deletes edge properties` -- edge property update
 * - `removeNode cascades to incident edges and rows` -- cascade delete
 * - `removeEdge on unknown id is a no-op` -- absent edge
 * - `clear empties every table including tombstones` -- reset
 * - `promoteNode copies the frozen row into the active layer in first-set order` -- promotion
 * - `promoted node takes edits and deletions in its active row` -- writes after promotion
 */
internal class ActiveLayerTest {
    private lateinit var layer: ActiveLayer

    @BeforeTest
    fun setUp() {
        layer = ActiveLayer()
    }

    @Test
    fun `addNode registers empty adjacency and stores the row`() {
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
    fun `setNodeProperties with null removes the name from the row`() {
        layer.addNode(1, mapOf("a" to 1.intVal, "b" to 2.intVal))

        layer.setNodeProperties(1, mapOf("a" to null, "c" to 3.intVal))

        assertEquals(mapOf("b" to 2.intVal, "c" to 3.intVal), layer.collectNodeProperties(1))
        assertNull(layer.nodeRows[1, "a"])
        assertFalse(layer.nodeRows.contains(1, "a"))
    }

    @Test
    fun `setEdgeProperties overlays and deletes edge properties`() {
        layer.addNode(1, emptyMap())
        layer.addNode(2, emptyMap())
        layer.addEdge(10, IStorage.EdgeStructure(1, 2, "t"), mapOf("a" to 1.intVal))

        layer.setEdgeProperties(10, mapOf("a" to null, "b" to 2.intVal))

        assertEquals(mapOf("b" to 2.intVal), layer.collectEdgeProperties(10))
    }

    @Test
    fun `removeNode cascades to incident edges and rows`() {
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
        assertFalse(layer.nodeRows.holdsAny(1))
        assertFalse(layer.edgeRows.holdsAny(10))
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
        assertFalse(layer.nodeRows.holdsAny(1))
        assertFalse(layer.edgeRows.holdsAny(10))
        assertTrue(layer.metaProperties.isEmpty())
        assertTrue(layer.deletedMetaNames.isEmpty())
    }

    @Test
    fun `promoteNode copies the frozen row into the active layer in first-set order`() {
        val stack = frozenStack()

        stack.promoteNode(1)

        assertTrue(stack.active.containsNode(1))
        assertEquals(listOf("b", "a"), stack.active.nodeRows.collect(1).keys.toList())
        assertEquals(mapOf("b" to 1.intVal, "a" to 2.intVal), stack.nodeProperties(1))
        assertEquals(2.intVal, stack.nodeProperty(1, "a"))
        stack.promoteNode(1)
        assertEquals(listOf("b", "a"), stack.active.nodeRows.collect(1).keys.toList())
    }

    @Test
    fun `promoted node takes edits and deletions in its active row`() {
        val stack = frozenStack()
        stack.promoteNode(1)

        stack.active.setNodeProperties(1, linkedMapOf("b" to null, "c" to 3.intVal))

        assertEquals(listOf("a", "c"), stack.nodeProperties(1).keys.toList())
        assertNull(stack.nodeProperty(1, "b"))
        assertEquals(mapOf("b" to 1.intVal, "a" to 2.intVal), stack.frozen?.nodeProperties(1))
        stack.active.setNodeProperties(1, mapOf("a" to null, "c" to null))
        assertTrue(stack.nodeProperties(1).isEmpty())
        assertFalse(stack.active.nodeRows.holdsAny(1))
    }

    // A layer stack whose frozen layer holds node 1 with properties b then a.
    private fun frozenStack(): LayerStack {
        val source = ActiveLayer()
        source.addNode(1, linkedMapOf("b" to 1.intVal, "a" to 2.intVal))
        val stack = LayerStack { null }
        stack.frozen = FrozenLayer.merge(null, source, NativeStorageImpl())
        return stack
    }
}
