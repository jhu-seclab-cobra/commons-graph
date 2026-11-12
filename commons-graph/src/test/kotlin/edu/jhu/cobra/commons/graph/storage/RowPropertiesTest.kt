package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
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
 * Tests for RowProperties: the per-entity property rows of the storages and RowViewMap.
 *
 * - `row view isEmpty returns true when entity has no properties` -- empty view
 * - `row view containsKey returns true for existing and false for absent` -- containsKey paths
 * - `row view get returns value for existing and null for absent` -- get hit and miss
 * - `row view entries caches result on second access` -- caching
 * - `setNodeProperties adds a name to an existing row` -- row growth
 * - `deleteNode leaves no property for a later node` -- row removal
 * - `deleteNode keeps the rows of other entities` -- row isolation
 * - `deleteNode with no incident edges succeeds` -- empty adjacency sets
 * - `rows keep first-set order and a name set again moves to the end` -- iteration order
 * - `set applies updates and deletes and drops an emptied row` -- set, overwrite, delete, remove, clear
 * - `reads and removal of one row look up that row once and iterate no other row` -- cost per read
 * - `rows share one name instance per store until clear` -- name pool
 * - `NativeStorageImpl reads match a model after every mutation` -- storage paths and transfer
 * - `NativeConcurStorageImpl reads match a model after every mutation` -- storage paths and transfer
 * - `NativeConcurStorageImpl shares one name instance across entities` -- name pool under the lock
 * - `ActiveLayer reads match a model after every mutation` -- layer paths and clear
 * - `layered reads match a model across freezes` -- freeze merge with edits and deletions
 */
internal class RowPropertiesTest {
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
    }

    // region RowViewMap

    @Test
    fun `row view isEmpty returns true when entity has no properties`() {
        val nodeId = storage.addNode()

        val props = storage.getNodeProperties(nodeId)

        assertTrue(props.isEmpty())
    }

    @Test
    fun `row view containsKey returns true for existing and false for absent`() {
        val nodeId = storage.addNode(mapOf("name" to "Alice".strVal))

        val props = storage.getNodeProperties(nodeId)

        assertTrue(props.containsKey("name"))
        assertFalse(props.containsKey("absent"))
    }

    @Test
    fun `row view get returns value for existing and null for absent`() {
        val nodeId = storage.addNode(mapOf("name" to "Alice".strVal))

        val props = storage.getNodeProperties(nodeId)

        assertEquals("Alice", (props["name"] as StrVal).core)
        assertNull(props["absent"])
    }

    @Test
    fun `row view entries caches result on second access`() {
        val nodeId = storage.addNode(mapOf("name" to "Alice".strVal))

        val props = storage.getNodeProperties(nodeId)

        assertSame(props.entries, props.entries)
    }

    // endregion

    // region Storage rows

    @Test
    fun `setNodeProperties adds a name to an existing row`() {
        val n1 = storage.addNode(mapOf("a" to "1".strVal))

        storage.setNodeProperties(n1, mapOf("b" to "2".strVal))

        assertEquals("1", (storage.getNodeProperty(n1, "a") as StrVal).core)
        assertEquals("2", (storage.getNodeProperty(n1, "b") as StrVal).core)
    }

    @Test
    fun `deleteNode leaves no property for a later node`() {
        val n1 = storage.addNode(mapOf("unique" to "only".strVal))

        storage.deleteNode(n1)

        val n2 = storage.addNode()
        assertNull(storage.getNodeProperty(n2, "unique"))
        assertTrue(storage.getNodeProperties(n2).isEmpty())
    }

    @Test
    fun `deleteNode keeps the rows of other entities`() {
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

    // region RowProperties

    @Test
    fun `rows keep first-set order and a name set again moves to the end`() {
        val rows = RowProperties()
        rows.set(1, linkedMapOf("b" to 1.intVal, "a" to 2.intVal))
        rows.set(2, mapOf("c" to 3.intVal))

        rows.set(1, linkedMapOf("b" to 4.intVal, "d" to 5.intVal))

        assertEquals(listOf("b", "a", "d"), rows.collect(1).keys.toList())
        assertEquals(listOf("b", "a", "d"), rows.view(1).keys.toList())
        rows.set(1, mapOf("b" to null))
        rows.set(1, mapOf("b" to 6.intVal))
        assertEquals(listOf("a", "d", "b"), rows.collect(1).keys.toList())
        assertEquals(listOf("a", "d", "b"), rows.view(1).keys.toList())
        assertTrue(rows.view(3).isEmpty())
    }

    @Test
    fun `set applies updates and deletes and drops an emptied row`() {
        val rows = RowProperties()
        val model = LinkedHashMap<String, IValue>()
        fun step(updates: Map<String, IValue?>) {
            rows.set(1, updates)
            applyUpdates(model, updates)
            assertRow(model, rows, 1)
        }
        rows.set(2, mapOf("a" to 3.intVal))

        step(linkedMapOf("a" to 1.intVal, "b" to 2.intVal))
        step(linkedMapOf("a" to 4.intVal, "b" to null, "x" to null))
        step(linkedMapOf("c" to 5.intVal, "a" to null, "d" to 6.intVal))
        step(emptyMap())
        step(linkedMapOf("c" to null, "d" to null))
        assertFalse(rows.holdsAny(1))
        assertEquals(mapOf("a" to 3.intVal), rows.collect(2))

        rows.remove(2)
        rows.remove(9)
        assertFalse(rows.holdsAny(2))
        rows.set(3, mapOf("c" to 5.intVal))
        rows.clear()
        assertFalse(rows.holdsAny(3))
        assertNull(rows[3, "c"])
    }

    @Test
    fun `reads and removal of one row look up that row once and iterate no other row`() {
        val table = CountingRows()
        val rows = RowProperties(table)
        for (id in 1..1_000) rows.set(id, mapOf("p$id" to id.intVal))
        rows.set(0, linkedMapOf("a" to 1.intVal, "b" to 2.intVal))
        fun lookups(read: () -> Unit): Int {
            table.lookups = 0
            read()
            return table.lookups
        }
        table.iterations = 0

        assertEquals(1, lookups { assertEquals(listOf("a", "b"), rows.view(0).entries.map { it.key }) })
        assertEquals(1, lookups { assertEquals(2, rows.collect(0).size) })
        assertEquals(1, lookups { assertFalse(rows.view(0).isEmpty()) })
        assertEquals(1, lookups { assertEquals(1.intVal, rows[0, "a"]) })
        assertEquals(1, lookups { assertTrue(rows.contains(0, "b")) })
        assertEquals(1, lookups { rows.set(0, mapOf("a" to 3.intVal)) })
        assertEquals(1, lookups { rows.remove(0) })
        assertEquals(0, table.iterations)
    }

    @Test
    fun `rows share one name instance per store until clear`() {
        val rows = RowProperties()
        val first = String(charArrayOf('n'))
        rows.set(1, mapOf(first to 1.intVal))
        rows.set(2, mapOf(String(charArrayOf('n')) to 2.intVal))

        assertSame(first, rows.collect(2).keys.single())
        rows.remove(1)
        rows.remove(2)
        rows.set(3, mapOf(String(charArrayOf('n')) to 3.intVal))
        assertSame(first, rows.collect(3).keys.single())
        rows.clear()
        val later = String(charArrayOf('n'))
        rows.set(4, mapOf(later to 4.intVal))
        assertSame(later, rows.collect(4).keys.single())
    }

    // endregion

    // region Storage paths

    @Test
    fun `NativeStorageImpl reads match a model after every mutation`() {
        mutate(storage, NativeStorageImpl())
    }

    @Test
    fun `NativeConcurStorageImpl reads match a model after every mutation`() {
        mutate(NativeConcurStorageImpl(), NativeConcurStorageImpl())
    }

    @Test
    fun `NativeConcurStorageImpl shares one name instance across entities`() {
        val concur = NativeConcurStorageImpl()
        val first = String(charArrayOf('n'))
        val n1 = concur.addNode(mapOf(first to 1.intVal))
        val n2 = concur.addNode()
        concur.setNodeProperties(n2, mapOf(String(charArrayOf('n')) to 2.intVal))
        val edgeName = String(charArrayOf('e'))
        concur.addEdge(n1, n2, "t", mapOf(edgeName to 3.intVal))
        val e2 = concur.addEdge(n2, n1, "t", mapOf(String(charArrayOf('e')) to 4.intVal))

        assertSame(first, concur.getNodeProperties(n2).keys.single())
        assertSame(edgeName, concur.getEdgeProperties(e2).keys.single())
    }

    @Test
    fun `ActiveLayer reads match a model after every mutation`() {
        val layer = ActiveLayer()
        val nodes = HashMap<Int, LinkedHashMap<String, IValue>>()
        val edges = HashMap<Int, LinkedHashMap<String, IValue>>()
        val check = {
            for (id in layer.nodeIds) {
                assertRow(nodes.getValue(id), layer.nodeRows, id)
                assertEquals(nodes.getValue(id), layer.collectNodeProperties(id))
            }
            for (id in layer.edgeEndpoints.keys) {
                assertRow(edges.getValue(id), layer.edgeRows, id)
                assertEquals(edges.getValue(id), layer.collectEdgeProperties(id))
            }
            assertEquals(nodes.keys, layer.nodeIds)
            assertEquals(edges.keys, layer.edgeEndpoints.keys)
        }
        nodes[1] = linkedMapOf("n" to 1.intVal)
        layer.addNode(1, nodes.getValue(1))
        nodes[2] = linkedMapOf()
        layer.addNode(2, emptyMap())
        edges[10] = linkedMapOf("e" to 1.intVal, "f" to 2.intVal)
        layer.addEdge(10, IStorage.EdgeStructure(1, 2, "t"), edges.getValue(10))
        edges[11] = linkedMapOf("e" to 3.intVal)
        layer.addEdge(11, IStorage.EdgeStructure(2, 1, "t"), edges.getValue(11))
        check()
        layer.setNodeProperties(2, linkedMapOf("n" to 2.intVal, "m" to 3.intVal))
        applyUpdates(nodes.getValue(2), linkedMapOf("n" to 2.intVal, "m" to 3.intVal))
        layer.setEdgeProperties(10, linkedMapOf("f" to null, "g" to 4.intVal))
        applyUpdates(edges.getValue(10), linkedMapOf("f" to null, "g" to 4.intVal))
        check()
        layer.removeEdge(11)
        edges.remove(11)
        check()
        layer.removeNode(1)
        nodes.remove(1)
        edges.remove(10)
        check()
        assertFalse(layer.edgeRows.holdsAny(10))
        assertFalse(layer.nodeRows.holdsAny(1))
        layer.clear()
        nodes.clear()
        check()
        assertFalse(layer.nodeRows.holdsAny(2))
    }

    @Test
    fun `layered reads match a model across freezes`() {
        val layered = LayeredStorageImpl()
        val n1 = layered.addNode(mapOf("n" to 1.intVal))
        val n2 = layered.addNode(mapOf("n" to 2.intVal, "m" to 3.intVal))
        val e1 = layered.addEdge(n1, n2, "t", mapOf("e" to 1.intVal))
        val e2 = layered.addEdge(n2, n1, "t", mapOf("e" to 2.intVal, "f" to 3.intVal))
        layered.freeze()
        layered.setNodeProperties(n1, mapOf("n" to null, "k" to 4.intVal))
        layered.setEdgeProperties(e1, mapOf("e" to null, "g" to 5.intVal))
        layered.deleteEdge(e2)
        val n3 = layered.addNode(mapOf("n" to 6.intVal))
        val e3 = layered.addEdge(n3, n2, "t", mapOf("e" to 7.intVal))
        layered.freeze()

        assertProperties(mapOf("k" to 4.intVal), layered.getNodeProperties(n1), ordered = false)
        assertProperties(mapOf("n" to 2.intVal, "m" to 3.intVal), layered.getNodeProperties(n2), ordered = false)
        assertProperties(mapOf("n" to 6.intVal), layered.getNodeProperties(n3), ordered = false)
        assertProperties(mapOf("g" to 5.intVal), layered.getEdgeProperties(e1), ordered = false)
        assertProperties(mapOf("e" to 7.intVal), layered.getEdgeProperties(e3), ordered = false)
        assertFalse(layered.containsEdge(e2))
    }

    // endregion

    // region Helpers

    // Counts row lookups by entity id and iterations over the whole row table.
    private class CountingRows : HashMap<Int, Array<Any?>>() {
        var lookups = 0
        var iterations = 0

        override fun get(key: Int): Array<Any?>? {
            lookups++
            return super.get(key)
        }

        override fun containsKey(key: Int): Boolean {
            lookups++
            return super.containsKey(key)
        }

        override fun put(
            key: Int,
            value: Array<Any?>,
        ): Array<Any?>? {
            lookups++
            return super.put(key, value)
        }

        override fun remove(key: Int): Array<Any?>? {
            lookups++
            return super.remove(key)
        }

        override val entries: MutableSet<MutableMap.MutableEntry<Int, Array<Any?>>>
            get() {
                iterations++
                return super.entries
            }

        override val keys: MutableSet<Int>
            get() {
                iterations++
                return super.keys
            }

        override val values: MutableCollection<Array<Any?>>
            get() {
                iterations++
                return super.values
            }
    }

    // Applies [updates] to [model] as a storage does: null deletes, a new name goes to the end.
    private fun applyUpdates(
        model: MutableMap<String, IValue>,
        updates: Map<String, IValue?>,
    ) {
        for ((name, value) in updates) if (value == null) model.remove(name) else model[name] = value
    }

    private fun assertRow(
        expected: Map<String, IValue>,
        rows: RowProperties,
        id: Int,
    ) {
        assertEquals(expected.isNotEmpty(), rows.holdsAny(id))
        assertEquals(expected.keys.toList(), rows.collect(id).keys.toList())
        for ((name, value) in expected) {
            assertEquals(value, rows[id, name])
            assertTrue(rows.contains(id, name))
        }
        assertNull(rows[id, "absent"])
        assertFalse(rows.contains(id, "absent"))
        assertProperties(expected, rows.view(id), ordered = true)
    }

    // Checks a property read against [expected]: emptiness before entries, then contents, named reads and order.
    private fun assertProperties(
        expected: Map<String, IValue>,
        actual: Map<String, IValue>,
        ordered: Boolean,
    ) {
        assertEquals(expected.isEmpty(), actual.isEmpty())
        for ((name, value) in expected) {
            assertEquals(value, actual[name])
            assertTrue(actual.containsKey(name))
        }
        assertNull(actual["absent"])
        assertFalse(actual.containsKey("absent"))
        assertEquals(expected, actual)
        if (ordered) assertEquals(expected.keys.toList(), actual.keys.toList())
    }

    private fun assertStorage(
        storage: IStorage,
        nodes: Map<Int, Map<String, IValue>>,
        edges: Map<Int, Map<String, IValue>>,
    ) {
        assertEquals(nodes.keys, storage.nodeIDs)
        assertEquals(edges.keys, storage.edgeIDs)
        for ((id, expected) in nodes) {
            assertProperties(expected, storage.getNodeProperties(id), ordered = true)
            for ((name, value) in expected) assertEquals(value, storage.getNodeProperty(id, name))
        }
        for ((id, expected) in edges) {
            assertProperties(expected, storage.getEdgeProperties(id), ordered = true)
            for ((name, value) in expected) assertEquals(value, storage.getEdgeProperty(id, name))
        }
    }

    // Applies every mutation path of a storage to [source], copies it into [target], and checks both against a model.
    private fun mutate(
        source: IStorage,
        target: IStorage,
    ) {
        val nodes = HashMap<Int, LinkedHashMap<String, IValue>>()
        val edges = HashMap<Int, LinkedHashMap<String, IValue>>()
        fun node(properties: LinkedHashMap<String, IValue>): Int = source.addNode(properties).also { nodes[it] = LinkedHashMap(properties) }
        fun edge(
            src: Int,
            dst: Int,
            properties: LinkedHashMap<String, IValue>,
        ): Int = source.addEdge(src, dst, "t", properties).also { edges[it] = LinkedHashMap(properties) }
        fun setNode(
            id: Int,
            updates: Map<String, IValue?>,
        ) {
            source.setNodeProperties(id, updates)
            applyUpdates(nodes.getValue(id), updates)
        }
        val check = { assertStorage(source, nodes, edges) }

        val n1 = node(linkedMapOf("n" to 1.intVal, "m" to 2.intVal))
        val n2 = node(linkedMapOf())
        val n3 = node(linkedMapOf("n" to 3.intVal))
        val e1 = edge(n1, n2, linkedMapOf("e" to 1.intVal))
        val e2 = edge(n2, n3, linkedMapOf("e" to 2.intVal, "f" to 3.intVal))
        edge(n3, n1, linkedMapOf("f" to 4.intVal))
        check()
        setNode(n1, linkedMapOf("n" to 5.intVal, "m" to null, "k" to 6.intVal))
        setNode(n2, linkedMapOf("x" to null))
        source.setEdgeProperties(e2, linkedMapOf("e" to null, "g" to 7.intVal))
        applyUpdates(edges.getValue(e2), linkedMapOf("e" to null, "g" to 7.intVal))
        check()
        setNode(n1, mapOf("m" to 8.intVal))
        check()

        val nodeIds = source.transferTo(target)
        val sourceOf = nodeIds.entries.associate { (from, to) -> to to from }
        val edgeIds = target.edgeIDs.associateBy { id ->
            val structure = target.getEdgeStructure(id)
            source.edgeIDs.single {
                val ends = source.getEdgeStructure(it)
                ends.src == sourceOf[structure.src] && ends.dst == sourceOf[structure.dst]
            }
        }
        val transferred = nodes.entries.associate { (id, props) -> nodeIds.getValue(id) to LinkedHashMap(props) }
        val transferredEdges = edges.entries.associate { (id, props) -> edgeIds.getValue(id) to LinkedHashMap(props) }
        assertStorage(target, transferred, transferredEdges)

        source.deleteEdge(e1)
        edges.remove(e1)
        check()
        source.deleteNode(n3)
        nodes.remove(n3)
        edges.keys.retainAll(source.edgeIDs)
        assertEquals(emptySet(), edges.keys)
        check()
        setNode(n1, mapOf("n" to null, "k" to null, "m" to null))
        check()
        source.clear()
        nodes.clear()
        check()
        node(linkedMapOf("z" to 1.intVal))
        check()
        assertStorage(target, transferred, transferredEdges)
    }

    // endregion
}
