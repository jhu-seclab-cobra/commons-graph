/*
 * Selected NAME relation tests:
 * - projection readiness follows constructor through delegation.
 * - factory constructors preserve published descriptors.
 * - exact selection ignores unrelated siblings.
 * - first attachment ignores unrelated siblings.
 * - same key in other containers stays unrelated.
 * - colliding keys stay distinct.
 * - freeze and rollback restore exact rows.
 * - multi kind selection preserves all physical members through delegation freeze and rollback.
 * - conflicting attach leaves storage unchanged.
 * - attached fields cannot change.
 * - import remaps rows and shares one physical access.
 * - keyed access retains only rows and reads only their fixed fields.
 */
/* Fixed exact relation reads and first attachment on the existing storage representation.
 * - `exact selection ignores unrelated siblings`: preserve one selected row without sibling work.
 * - `first attachment ignores unrelated siblings`: create one member and relation without sibling work.
 * - `same key in other containers stays unrelated`: require the container in the exact lookup key.
 * - `colliding keys stay distinct`: preserve complete kind/key equality and incident-edge deletion.
 * - `freeze and rollback restore exact rows`: selected identity follows tombstones and active-layer removal.
 * - `conflicting attach leaves storage unchanged`: reject a different target before relation mutation.
 * - `attached fields cannot change`: prevent unsupported mutable rekeying.
 * - `import remaps rows and shares one physical access`: transfer builds the projection during row import.
 * - `keyed access retains only rows and reads only their fixed fields`: count setup, selected work and miss retention.
 * - `factory constructors preserve published descriptors`: retain source and JVM factory/default construction.
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class ExactAdjacencyTest {
    @Test
    fun `projection readiness follows constructor through delegation`() {
        val declaration = AdjacencyProjection("contains", "kind", "key")
        for (storage in listOf(NativeStorageImpl(), LayeredStorageImpl())) {
            storage.addNode()
            assertEquals(null, storage.adjacencyProjection)
        }
        for (storage in listOf(NativeStorageImpl(declaration), LayeredStorageImpl(declaration))) {
            val delegated = object : ExactAdjacencyStorage by storage {}
            assertEquals(declaration, delegated.adjacencyProjection)
            val source = storage.addNode()
            assertEquals(emptySet(), delegated.getExactOutgoingEdges(source, "kind".strVal, "key".strVal))
            assertEquals(emptySet(), delegated.getExactOutgoingEdges(source, setOf("kind".strVal), "key".strVal))
        }
    }

    @Test
    fun `factory constructors preserve published descriptors`() {
        var calls = 0
        val factory: () -> IStorage = {
            calls++
            NativeStorageImpl()
        }
        val positional = LayeredStorageImpl(factory)
        val named = LayeredStorageImpl(frozenLayerFactory = factory)
        val trailing = LayeredStorageImpl {
            calls++
            NativeStorageImpl()
        }
        for (storage in listOf(positional, named, trailing)) {
            storage.addNode()
            storage.freeze()
        }
        assertEquals(3, calls)
        val type = LayeredStorageImpl::class.java
        val factoryDescriptor = type.getConstructor(Function0::class.java)
        val linked = factoryDescriptor.newInstance(factory)
        linked.addNode()
        linked.freeze()
        assertEquals(4, calls, "the original factory descriptor reaches the supplied factory")
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val defaultDescriptor = type.getConstructor(Function0::class.java, Int::class.javaPrimitiveType, marker)
        val oldDefaultCall = defaultDescriptor.newInstance(null, 1, null)
        oldDefaultCall.addNode()
        oldDefaultCall.freeze()
        assertEquals(2, oldDefaultCall.layerCount, "the old Kotlin default call descriptor remains valid")
        val javaDefault = type.getConstructor().newInstance()
        assertEquals(1, javaDefault.layerCount, "Java no-argument construction remains valid")
        assertEquals(1, LayeredStorageImpl().layerCount, "Kotlin no-argument construction remains valid")
        val projectionDescriptor = type.getConstructor(AdjacencyProjection::class.java, Function0::class.java)
        val configured = projectionDescriptor.newInstance(PROJECTION, factory)
        assertEquals(PROJECTION, configured.adjacencyProjection)
        val projectionDefaults = type.getConstructor(
            AdjacencyProjection::class.java, Function0::class.java, Int::class.javaPrimitiveType, marker,
        )
        val defaultProjectedCall = projectionDefaults.newInstance(PROJECTION, null, 2, null)
        val originalRead = ExactAdjacencyStorage::class.java.getMethod(
            "getExactOutgoingEdges", Int::class.javaPrimitiveType, IValue::class.java, IValue::class.java,
        )
        val source = defaultProjectedCall.addNode()
        assertEquals(emptySet<Int>(), originalRead.invoke(defaultProjectedCall, source, FUNCTION, key("absent")))
        assertEquals(null, LayeredStorageImpl(null).adjacencyProjection)
    }

    @Test
    fun `exact selection ignores unrelated siblings`() {
        for (factory in factories) {
            val observations = widths.map { width ->
                val fixture = fixture(factory(), width, false)
                val counted = Counted(fixture.storage)
                assertEquals(setOf(fixture.edge), select(counted, fixture.source, key("selected")))
                counted.counts()
            }
            println("EXACT_SELECT widths=$widths counts=$observations")
            assertEquals(List(widths.size) { observations.first() }, observations)
        }
    }

    @Test
    fun `first attachment ignores unrelated siblings`() {
        for (factory in factories) {
            val observations = widths.map { width ->
                val fixture = fixture(factory(), width, false)
                val counted = Counted(fixture.storage)
                val edge = attach(counted, fixture.source, key("created"))
                assertEquals(key("created"), counted.getNodeProperty(counted.getEdgeStructure(edge).dst, KEY))
                val beforeRepeat = fixture.storage.edgeIDs.size
                assertEquals(edge, attach(fixture.storage, fixture.source, key("created")))
                assertEquals(beforeRepeat, fixture.storage.edgeIDs.size)
                counted.counts()
            }
            println("EXACT_ATTACH widths=$widths counts=$observations")
            assertEquals(List(widths.size) { observations.first() }, observations)
        }
    }

    @Test
    fun `same key in other containers stays unrelated`() {
        for (factory in factories) {
            val observations = widths.map { width ->
                val fixture = fixture(factory(), width, true)
                val counted = Counted(fixture.storage)
                assertEquals(setOf(fixture.edge), select(counted, fixture.source, key("selected")))
                counted.counts()
            }
            assertEquals(List(widths.size) { observations.first() }, observations)
        }
    }

    @Test
    fun `colliding keys stay distinct`() {
        assertEquals(key("Aa").hashCode(), key("BB").hashCode())
        for (factory in factories) {
            val storage = factory()
            val source = storage.addNode()
            val first = attach(storage, source, key("Aa"))
            val second = attach(storage, source, key("BB"))
            assertEquals(setOf(first), select(storage, source, key("Aa")))
            assertEquals(setOf(second), select(storage, source, key("BB")))
            val classKind = "NAME_CLASS".strVal
            val classNode = storage.addNode(properties(key("Aa")) + (KIND to classKind))
            val classEdge = exact(storage).attachExact(source, classNode)
            assertEquals(setOf(classEdge), exact(storage).getExactOutgoingEdges(source, classKind, key("Aa")))
            assertEquals(setOf(first), select(storage, source, key("Aa")))
            storage.deleteNode(classNode)
            assertEquals(emptySet(), exact(storage).getExactOutgoingEdges(source, classKind, key("Aa")))
            storage.deleteEdge(first)
            assertEquals(emptySet(), select(storage, source, key("Aa")))
            assertEquals(setOf(second), select(storage, source, key("BB")))
        }
    }

    @Test
    fun `freeze and rollback restore exact rows`() {
        val storage = LayeredStorageImpl(projection = PROJECTION)
        val fixture = fixture(storage, 8, false)
        storage.setEdgeProperties(fixture.edge, mapOf("payload" to "kept".strVal))
        val before = storage.getEdgeProperties(fixture.edge)
        storage.freeze()
        storage.deleteEdge(fixture.edge)
        assertEquals(emptySet(), select(storage, fixture.source, key("selected")))
        val active = attach(storage, fixture.source, key("selected"))
        assertEquals(setOf(active), select(storage, fixture.source, key("selected")))
        storage.clearActiveLayer()
        assertEquals(setOf(fixture.edge), select(storage, fixture.source, key("selected")))
        assertEquals(before, storage.getEdgeProperties(fixture.edge))
        assertEquals(9, storage.indexedEdgeCount)
        storage.freeze()
        assertEquals(setOf(fixture.edge), select(storage, fixture.source, key("selected")))
    }

    @Test
    fun `multi kind selection preserves all physical members through delegation freeze and rollback`() {
        for (storage in listOf(NativeStorageImpl(PROJECTION), LayeredStorageImpl(PROJECTION))) {
            val source = storage.addNode()
            val kinds = linkedSetOf<IValue>(FUNCTION, "property".strVal)
            val edges = kinds.map { kind ->
                val node = storage.addNode(mapOf(KIND to kind, KEY to key("selected")))
                storage.addEdge(source, node, PROJECTION.edgeTag, emptyMap())
            }.toSet()
            val delegated = object : ExactAdjacencyStorage by storage {}
            val counted = Counted(delegated)
            assertEquals(edges, counted.getExactOutgoingEdges(source, kinds, key("selected")))
            assertEquals(listOf(0, 0, 0, 0, 0, edges.size), counted.counts())
            assertEquals(edges, delegated.getExactOutgoingEdges(source, kinds.reversed().toSet(), key("selected")))
            if (storage !is LayeredStorageImpl) continue
            storage.freeze()
            storage.deleteEdge(edges.first())
            assertEquals(edges - edges.first(), delegated.getExactOutgoingEdges(source, kinds, key("selected")))
            storage.clearActiveLayer()
            assertEquals(edges, delegated.getExactOutgoingEdges(source, kinds, key("selected")))
        }
    }

    @Test
    fun `conflicting attach leaves storage unchanged`() {
        for (factory in factories) {
            val fixture = fixture(factory(), 8, false)
            val target = fixture.storage.addNode(properties(key("selected")))
            val before = fixture.storage.edgeIDs.toSet()
            assertFailsWith<IllegalArgumentException> { exact(fixture.storage).attachExact(fixture.source, target) }
            assertEquals(before, fixture.storage.edgeIDs)
            assertTrue(fixture.storage.containsNode(target), "the supplied target remains caller-owned")
            val incomplete = fixture.storage.addNode(mapOf(KIND to FUNCTION))
            assertFailsWith<IllegalArgumentException> { exact(fixture.storage).attachExact(fixture.source, incomplete) }
            assertEquals(before, fixture.storage.edgeIDs)
        }
    }

    @Test
    fun `attached fields cannot change`() {
        for (factory in factories) {
            val fixture = fixture(factory(), 4, false)
            val target = fixture.storage.getEdgeStructure(fixture.edge).dst
            assertFailsWith<IllegalArgumentException> {
                fixture.storage.setNodeProperties(target, mapOf(KEY to key("different")))
            }
            assertEquals(setOf(fixture.edge), select(fixture.storage, fixture.source, key("selected")))
            fixture.storage.setNodeProperties(target, mapOf("other" to "allowed".strVal))
            assertEquals("allowed".strVal, fixture.storage.getNodeProperty(target, "other"))
        }
    }

    @Test
    fun `import remaps rows and shares one physical access`() {
        for (factory in factories) {
            val source = fixture(factory(), 4, true)
            val target = factory()
            target.addNode()
            val mapping = source.storage.transferTo(target)
            val first = object : ExactAdjacencyStorage by exact(target) {}
            val second = object : ExactAdjacencyStorage by exact(target) {}
            val selected = first.getExactOutgoingEdges(mapping.getValue(source.source), FUNCTION, key("selected"))
            assertEquals(1, selected.size)
            val repeated = second.getExactOutgoingEdges(mapping.getValue(source.source), FUNCTION, key("selected"))
            assertEquals(selected, repeated)
            assertEquals(selected, first.getExactOutgoingEdges(mapping.getValue(source.source), setOf(FUNCTION), key("selected")))
            assertEquals(selected, second.getExactOutgoingEdges(mapping.getValue(source.source), setOf(FUNCTION), key("selected")))
            val edge = target.getEdgeStructure(selected.single())
            assertEquals(mapping.getValue(source.storage.getEdgeStructure(source.edge).dst), edge.dst)
            assertEquals(target.getOutgoingEdges(edge.src), selected)
        }
    }

    @Test
    fun `keyed access retains only rows and reads only their fixed fields`() {
        val observations = widths.map { width ->
            val index = ExactAdjacencyIndex(PROJECTION)
            var propertiesRead = 0
            val rows = (0..width).associateWith { properties(key(if (it == 0) "selected" else "noise_$it")) }
            val read = { target: Int, field: String -> propertiesRead++; rows.getValue(target)[field] }
            for (target in rows.keys) {
                index.record(target, index.prepare(IStorage.EdgeStructure(20, target, RELATION), read))
            }
            assertEquals(2 * rows.size, propertiesRead, "setup reads two fields per relation already created")
            val before = propertiesRead
            repeat(8) {
                assertEquals(setOf(0), index.select(20, FUNCTION, key("selected")))
                assertEquals(emptySet(), index.select(20, FUNCTION, key("missing_$it")))
            }
            assertEquals(rows.size, index.size, "misses retain no history")
            val member = index.prepare(IStorage.EdgeStructure(20, 0, RELATION), read)
            assertEquals(0, index.existing(checkNotNull(member)))
            val operationReads = propertiesRead - before
            rows.keys.forEach(index::remove)
            assertEquals(0, index.size)
            operationReads
        }
        println("EXACT_INDEX widths=$widths selectedAndAttachPropertyReads=$observations")
        assertEquals(List(widths.size) { 2 }, observations)
    }

    private fun fixture(storage: IStorage, width: Int, otherContainers: Boolean): Fixture {
        val source = storage.addNode()
        val target = storage.addNode(properties(key("selected")))
        val edge = storage.addEdge(source, target, RELATION)
        repeat(width) { index ->
            val container = if (otherContainers) storage.addNode() else source
            val noiseKey = if (otherContainers) key("selected") else key("noise_$index")
            val noise = storage.addNode(properties(noiseKey))
            storage.addEdge(container, noise, RELATION)
        }
        return Fixture(storage, source, edge)
    }

    private fun select(storage: IStorage, source: Int, key: ListVal): Set<Int> =
        exact(storage).getExactOutgoingEdges(source, FUNCTION, key)

    private fun attach(storage: IStorage, source: Int, key: ListVal): Int {
        val previous = select(storage, source, key)
        if (previous.isNotEmpty()) return previous.single()
        val target = storage.addNode(properties(key))
        return exact(storage).attachExact(source, target)
    }

    private fun exact(storage: IStorage): ExactAdjacencyStorage = storage as ExactAdjacencyStorage

    private fun properties(key: ListVal): Map<String, IValue> = mapOf(KIND to FUNCTION, KEY to key)

    private fun key(text: String): ListVal =
        ListVal(value = listOf(ListVal(value = listOf(ListVal("lit".strVal, text.strVal)))))

    private class Fixture(val storage: IStorage, val source: Int, val edge: Int)

    private class Counted(private val base: IStorage) : ExactAdjacencyStorage, IStorage by base {
        override val adjacencyProjection: AdjacencyProjection? get() = (base as ExactAdjacencyReads).adjacencyProjection
        private var adjacencyReads = 0
        private var adjacencyItems = 0
        private var structures = 0
        private var properties = 0
        private var created = 0
        private var selected = 0

        override fun getExactOutgoingEdges(source: Int, kind: IValue, key: IValue): Set<Int> =
            (base as ExactAdjacencyStorage).getExactOutgoingEdges(source, kind, key).also { selected += it.size }

        override fun getExactOutgoingEdges(source: Int, kinds: Set<IValue>, key: IValue): Set<Int> =
            (base as ExactAdjacencyStorage).getExactOutgoingEdges(source, kinds, key).also { selected += it.size }

        override fun attachExact(source: Int, target: Int, properties: Map<String, IValue>): Int =
            (base as ExactAdjacencyStorage).attachExact(source, target, properties)

        override fun getOutgoingEdges(id: Int): Set<Int> = base.getOutgoingEdges(id).also {
            adjacencyReads++
            adjacencyItems += it.size
        }

        override fun getEdgeStructure(id: Int): IStorage.EdgeStructure = base.getEdgeStructure(id).also { structures++ }

        override fun getNodeProperty(id: Int, name: String): IValue? =
            base.getNodeProperty(id, name).also { properties++ }

        override fun addNode(properties: Map<String, IValue>): Int = base.addNode(properties).also { created++ }

        override fun addEdge(src: Int, dst: Int, tag: String, properties: Map<String, IValue>): Int =
            base.addEdge(src, dst, tag, properties).also { created++ }

        fun counts(): List<Int> = listOf(adjacencyReads, adjacencyItems, structures, properties, created, selected)
    }

    companion object {
        private const val RELATION = "CONTAIN_NAME"
        private const val KIND = "adg_type"
        private const val KEY = "adg_fold_key"
        private val FUNCTION: StrVal = "NAME_FUNCTION".strVal
        private val PROJECTION = AdjacencyProjection(RELATION, KIND, KEY)
        private val widths = listOf(0, 2, 4, 8)
        private val factories: List<() -> IStorage> =
            listOf({ NativeStorageImpl(PROJECTION) }, { LayeredStorageImpl(projection = PROJECTION) })
    }
}
