package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Tests for ColumnarProperties: columnar storage internals and ColumnViewMap.
 *
 * - `ColumnViewMap isEmpty returns true when entity has no properties` -- empty ColumnViewMap
 * - `ColumnViewMap containsKey returns true for existing and false for absent` -- containsKey paths
 * - `ColumnViewMap get returns value for existing and null for absent` -- get hit and miss
 * - `ColumnViewMap entries caches result on second access` -- caching
 * - `setColumnarProperties creates new column for first property of that name` -- column creation
 * - `removeEntityFromColumns removes column when it becomes empty` -- column cleanup
 * - `removeEntityFromColumns keeps column when other entities remain` -- column retention
 * - `deleteNode with no incident edges succeeds` -- empty adjacency sets
 * - `row names list the columns that hold the entity in first-set order` -- row index read
 * - `row names agree with columns after every store operation` -- set, overwrite, delete, remove, clear
 * - `NativeStorageImpl row names agree with columns after every mutation` -- storage paths and transfer
 * - `NativeConcurStorageImpl row names agree with columns after every mutation` -- storage paths and transfer
 * - `ActiveLayer row names agree with columns after every mutation` -- layer paths and clear
 * - `frozen storages keep row names equal to columns across freezes` -- freeze merge with edits and deletions
 */
internal class ColumnarPropertiesTest {
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
    }

    // region ColumnViewMap

    @Test
    fun `ColumnViewMap isEmpty returns true when entity has no properties`() {
        val nodeId = storage.addNode()

        val props = storage.getNodeProperties(nodeId)

        assertTrue(props.isEmpty())
    }

    @Test
    fun `ColumnViewMap containsKey returns true for existing and false for absent`() {
        val nodeId = storage.addNode(mapOf("name" to "Alice".strVal))

        val props = storage.getNodeProperties(nodeId)

        assertTrue(props.containsKey("name"))
        assertFalse(props.containsKey("absent"))
    }

    @Test
    fun `ColumnViewMap get returns value for existing and null for absent`() {
        val nodeId = storage.addNode(mapOf("name" to "Alice".strVal))

        val props = storage.getNodeProperties(nodeId)

        assertEquals("Alice", (props["name"] as StrVal).core)
        assertNull(props["absent"])
    }

    @Test
    fun `ColumnViewMap entries caches result on second access`() {
        val nodeId = storage.addNode(mapOf("name" to "Alice".strVal))

        val props = storage.getNodeProperties(nodeId)

        assertSame(props.entries, props.entries)
    }

    // endregion

    // region Columnar storage internals

    @Test
    fun `setColumnarProperties creates new column for first property of that name`() {
        val n1 = storage.addNode(mapOf("a" to "1".strVal))

        // "b" column does not exist yet; setNodeProperties creates it
        storage.setNodeProperties(n1, mapOf("b" to "2".strVal))

        assertEquals("1", (storage.getNodeProperty(n1, "a") as StrVal).core)
        assertEquals("2", (storage.getNodeProperty(n1, "b") as StrVal).core)
    }

    @Test
    fun `removeEntityFromColumns removes column when it becomes empty`() {
        val n1 = storage.addNode(mapOf("unique" to "only".strVal))

        storage.deleteNode(n1)

        // Add a new node — "unique" column should be gone, so no stale data
        val n2 = storage.addNode()
        assertNull(storage.getNodeProperty(n2, "unique"))
        assertTrue(storage.getNodeProperties(n2).isEmpty())
    }

    @Test
    fun `removeEntityFromColumns keeps column when other entities remain`() {
        val n1 = storage.addNode(mapOf("shared" to "A".strVal))
        val n2 = storage.addNode(mapOf("shared" to "B".strVal))

        storage.deleteNode(n1)

        assertEquals("B", (storage.getNodeProperty(n2, "shared") as StrVal).core)
    }

    @Test
    fun `deleteNode with no incident edges succeeds`() {
        val n1 = storage.addNode(mapOf("name" to "isolated".strVal))

        storage.deleteNode(n1)

        assertFalse(storage.containsNode(n1))
        assertTrue(storage.nodeIDs.isEmpty())
    }

    // endregion

    // region Per-entity column names

    @Test
    fun `row names list the columns that hold the entity in first-set order`() {
        val columns = ColumnarProperties()
        columns.set(1, mapOf("b" to 1.intVal, "a" to 2.intVal))
        columns.set(2, mapOf("c" to 3.intVal))

        columns.set(1, mapOf("b" to 4.intVal, "d" to 5.intVal))

        assertEquals(listOf("b", "a", "d"), columns.rowNames(1))
        assertEquals(listOf("b", "a", "d"), columns.view(1).keys.toList())
        assertEquals(emptyList(), columns.rowNames(3))
        assertTrue(columns.view(3).isEmpty())
    }

    @Test
    fun `row names agree with columns after every store operation`() {
        val columns = ColumnarProperties()
        columns.set(1, mapOf("a" to 1.intVal, "b" to 2.intVal))
        columns.set(2, mapOf("a" to 3.intVal))
        columns.checkRows()

        columns.set(1, mapOf("a" to 4.intVal, "b" to null, "x" to null))
        columns.checkRows()
        assertEquals(listOf("a"), columns.rowNames(1))
        assertFalse("b" in columns.names)

        columns.set(2, mapOf("a" to null))
        columns.checkRows()
        assertEquals(emptyList(), columns.rowNames(2))

        columns.remove(1)
        columns.remove(9)
        columns.checkRows()
        assertTrue(columns.names.isEmpty())

        columns.set(3, mapOf("c" to 5.intVal))
        columns.clear()
        columns.checkRows()
        assertEquals(emptyList(), columns.rowNames(3))
    }

    @Test
    fun `NativeStorageImpl row names agree with columns after every mutation`() {
        val target = NativeStorageImpl()
        mutate(storage, target) {
            storage.nodeColumns.checkRows()
            storage.edgeColumns.checkRows()
            target.nodeColumns.checkRows()
            target.edgeColumns.checkRows()
        }
    }

    @Test
    fun `NativeConcurStorageImpl row names agree with columns after every mutation`() {
        val source = NativeConcurStorageImpl()
        val target = NativeConcurStorageImpl()
        mutate(source, target) {
            source.nodeColumns.checkRows()
            source.edgeColumns.checkRows()
            target.nodeColumns.checkRows()
            target.edgeColumns.checkRows()
        }
    }

    @Test
    fun `ActiveLayer row names agree with columns after every mutation`() {
        val layer = ActiveLayer()
        val check = {
            layer.nodeColumns.checkRows()
            layer.edgeColumns.checkRows()
        }
        layer.addNode(1, mapOf("n" to 1.intVal))
        layer.addNode(2, emptyMap())
        layer.addEdge(10, IStorage.EdgeStructure(1, 2, "t"), mapOf("e" to 1.intVal, "f" to 2.intVal))
        layer.addEdge(11, IStorage.EdgeStructure(2, 1, "t"), mapOf("e" to 3.intVal))
        check()
        layer.setNodeProperties(2, mapOf("n" to 2.intVal, "m" to 3.intVal))
        layer.setEdgeProperties(10, mapOf("f" to null, "g" to 4.intVal))
        check()
        layer.removeEdge(11)
        check()
        layer.removeNode(1)
        check()
        assertEquals(emptyList(), layer.edgeColumns.rowNames(10))
        layer.clear()
        check()
        assertTrue(layer.nodeColumns.names.isEmpty())
    }

    @Test
    fun `frozen storages keep row names equal to columns across freezes`() {
        val frozen = ArrayList<NativeStorageImpl>()
        val layered = LayeredStorageImpl { NativeStorageImpl().also(frozen::add) }
        val n1 = layered.addNode(mapOf("n" to 1.intVal))
        val n2 = layered.addNode(mapOf("n" to 2.intVal, "m" to 3.intVal))
        val e1 = layered.addEdge(n1, n2, "t", mapOf("e" to 1.intVal))
        val e2 = layered.addEdge(n2, n1, "t", mapOf("e" to 2.intVal, "f" to 3.intVal))
        layered.freeze()
        layered.setNodeProperties(n1, mapOf("n" to null, "k" to 4.intVal))
        layered.setEdgeProperties(e1, mapOf("e" to null, "g" to 5.intVal))
        layered.deleteEdge(e2)
        val n3 = layered.addNode(mapOf("n" to 6.intVal))
        layered.addEdge(n3, n2, "t", mapOf("e" to 7.intVal))
        layered.freeze()

        for (storage in frozen) {
            storage.nodeColumns.checkRows()
            storage.edgeColumns.checkRows()
        }
        assertEquals(mapOf("k" to 4.intVal), layered.getNodeProperties(n1))
        assertEquals(mapOf("g" to 5.intVal), layered.getEdgeProperties(e1))
    }

    // Applies every mutation path of a storage to [source], copies it into [target], and runs [check] after each.
    private fun mutate(
        source: IStorage,
        target: IStorage,
        check: () -> Unit,
    ) {
        val n1 = source.addNode(mapOf("n" to 1.intVal, "m" to 2.intVal))
        val n2 = source.addNode()
        val n3 = source.addNode(mapOf("n" to 3.intVal))
        val e1 = source.addEdge(n1, n2, "t", mapOf("e" to 1.intVal))
        val e2 = source.addEdge(n2, n3, "t", mapOf("e" to 2.intVal, "f" to 3.intVal))
        source.addEdge(n3, n1, "t", mapOf("f" to 4.intVal))
        check()
        source.setNodeProperties(n1, mapOf("n" to 5.intVal, "m" to null, "k" to 6.intVal))
        source.setEdgeProperties(e2, mapOf("e" to null, "g" to 7.intVal))
        check()
        assertEquals(setOf("n", "k"), source.getNodeProperties(n1).keys)
        source.transferTo(target)
        check()
        source.deleteEdge(e1)
        check()
        source.deleteNode(n3)
        check()
        source.clear()
        check()
        assertEquals(setOf(setOf("n", "k"), emptySet(), setOf("n")), target.nodeIDs.map { target.getNodeProperties(it).keys }.toSet())
    }

    // endregion
}
