/* Storage group contracts:
 * core reads equal full filters through duplicate deletion, re-addition, node deletion and clear.
 * layered reads retain equality and snapshots through promotion, freeze, tombstones and revert.
 * reversed frozen adjacency retains pair and triple order through promotion, deletion and revert.
 * bucket growth and cluster deletion retain every matching row in encounter order.
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals

internal class TagGroupStorageTest {
    private val grouping: (String) -> String = { it.substringBefore('@') }

    @Test
    fun `core reads equal full filters through edge and node lifecycle`() {
        val stores = listOf(NativeStorageImpl(tagGroup = grouping), NativeConcurStorageImpl(grouping),
            LayeredStorageImpl(projection = null, tagGroup = grouping))
        for (store in stores) {
            val source = store.addNode()
            val target = store.addNode()
            val first = store.addEdge(source, target, "BIND_WITH@1")
            val other = store.addEdge(source, target, "OTHER")
            val duplicate = store.addEdge(source, target, "BIND_WITH@1")
            assertReads(store)
            store.deleteEdge(first)
            assertEquals(duplicate, store.findEdge(source, target, "BIND_WITH@1"))
            assertEquals(other, store.findEdge(source, target))
            assertReads(store)
            store.addEdge(source, target, "BIND_WITH@2")
            assertReads(store)
            store.deleteNode(target)
            assertReads(store)
            store.clear()
            assertEquals(emptySet(), store.edgeIDs)
            assertEquals(null, store.findEdge(source, target))
            store.addEdge(store.addNode(), store.addNode(), "BIND_WITH@1")
            assertReads(store)
        }
    }

    @Test
    fun `layered snapshots agree through freeze promotion tombstones and revert`() {
        for (factory in listOf<() -> IStorage>({ NativeStorageImpl() }, { NativeConcurStorageImpl() })) {
            val store = LayeredStorageImpl(projection = null, tagGroup = grouping, frozenLayerFactory = factory)
            val source = store.addNode()
            val target = store.addNode()
            val first = store.addEdge(source, target, "BIND_WITH@1")
            val second = store.addEdge(source, target, "BIND_WITH@2")
            store.addEdge(source, target, "OTHER")
            val snapshot = store.getOutgoingEdges(source, "BIND_WITH").toList()
            store.freeze()
            assertReads(store)
            store.setEdgeProperties(second, mapOf("p" to "promoted".strVal))
            store.addEdge(source, target, "BIND_WITH@3")
            store.deleteEdge(first)
            assertReads(store)
            store.clearActiveLayer()
            assertReads(store)
            assertEquals(snapshot, store.getOutgoingEdges(source, "BIND_WITH").toList())
            store.deleteEdge(first)
            store.freeze()
            assertReads(store)
            assertEquals(listOf(second), store.getOutgoingEdges(source, "BIND_WITH").toList())
        }
    }

    @Test
    fun `bucket resizing and cluster deletion preserve matching order`() {
        val store = NativeStorageImpl(tagGroup = grouping)
        val source = store.addNode()
        val target = store.addNode()
        val ids = (0 until 200).map { index -> store.addEdge(source, target, "g${index % 17}@$index") }
        for (id in ids.filterIndexed { index, _ -> index % 3 == 0 }) store.deleteEdge(id)
        assertReads(store)
        for (id in ids.filter { store.containsEdge(it) }) store.deleteEdge(id)
        assertReads(store)
    }

    @Test
    fun `reversed frozen adjacency retains pair and triple order through lifecycle`() {
        for (group in listOf(IDENTITY_TAG_GROUP, grouping)) {
            val store = LayeredStorageImpl(projection = null, tagGroup = group,
                frozenLayerFactory = { ReversedAdjacencyStorage(NativeStorageImpl(tagGroup = group)) })
            val source = store.addNode()
            val target = store.addNode()
            val first = store.addEdge(source, target, "BIND_WITH@1", mapOf("row" to "first".strVal))
            val second = store.addEdge(source, target, "BIND_WITH@1", mapOf("row" to "second".strVal))
            val other = store.addEdge(source, target, "OTHER")
            assertPairs(store)
            store.freeze()
            assertPairs(store)
            assertEquals(second, store.findEdge(source, target, "BIND_WITH@1"))
            assertEquals("second".strVal, store.getEdgeProperty(second, "row"))
            val snapshot = store.getEdges(source, target).toList()
            store.setEdgeProperties(first, mapOf("p" to "promoted".strVal))
            assertPairs(store)
            store.deleteEdge(second)
            store.deleteEdge(other)
            store.addEdge(source, target, "BIND_WITH@1")
            assertPairs(store)
            store.clearActiveLayer()
            assertPairs(store)
            assertEquals(snapshot, store.getEdges(source, target).toList())
            store.deleteEdge(first)
            store.freeze()
            assertPairs(store)
            store.addEdge(source, target, "OTHER")
            assertPairs(store)
            store.clear()
            assertEquals(null, store.findEdge(source, target))
        }
    }

    private fun assertPairs(store: IStorage) {
        for (source in store.nodeIDs) for (target in store.nodeIDs) {
            val pair = store.getOutgoingEdges(source).filter { store.getEdgeStructure(it).dst == target }
            assertEquals(pair, store.getEdges(source, target).toList())
            assertEquals(pair.firstOrNull(), store.findEdge(source, target))
            for (tag in pair.map { store.getEdgeStructure(it).tag } + "missing") {
                val triples = pair.filter { store.getEdgeStructure(it).tag == tag }
                assertEquals(triples, store.getEdges(source, target, tag).toList())
                assertEquals(triples.firstOrNull(), store.findEdge(source, target, tag))
            }
        }
    }

    private fun assertReads(store: IStorage) {
        for (node in store.nodeIDs) {
            for (group in listOf("BIND_WITH", "OTHER", "missing") + (0 until 17).map { "g$it" }) {
                val incoming = store.getIncomingEdges(node).filter { grouping(store.getEdgeStructure(it).tag) == group }
                val outgoing = store.getOutgoingEdges(node).filter { grouping(store.getEdgeStructure(it).tag) == group }
                assertEquals(incoming, store.getIncomingEdges(node, group).toList())
                assertEquals(outgoing, store.getOutgoingEdges(node, group).toList())
            }
            val selected = setOf("BIND_WITH", "OTHER", "g1", "g2")
            assertEquals(store.getOutgoingEdges(node).filter { grouping(store.getEdgeStructure(it).tag) in selected },
                store.getOutgoingEdges(node, selected).toList())
            assertEquals(store.getIncomingEdges(node).filter { grouping(store.getEdgeStructure(it).tag) in selected },
                store.getIncomingEdges(node, selected).toList())
            for (target in store.nodeIDs) {
                val pair = store.getOutgoingEdges(node).filter { store.getEdgeStructure(it).dst == target }
                assertEquals(pair, store.getEdges(node, target).toList())
                assertEquals(pair.firstOrNull(), store.findEdge(node, target))
                for (tag in pair.map { store.getEdgeStructure(it).tag } + "missing") {
                    val triples = pair.filter { store.getEdgeStructure(it).tag == tag }
                    assertEquals(triples, store.getEdges(node, target, tag).toList())
                    assertEquals(triples.firstOrNull(), store.findEdge(node, target, tag))
                }
            }
        }
    }
}

// A factory may expose an encounter order different from its insertion order.
private class ReversedAdjacencyStorage(private val base: IStorage) : IStorage by base {
    override fun getOutgoingEdges(id: Int): Set<Int> =
        base.getOutgoingEdges(id).reversed().toCollection(LinkedHashSet())
    override fun getIncomingEdges(id: Int): Set<Int> =
        base.getIncomingEdges(id).reversed().toCollection(LinkedHashSet())
    override fun getOutgoingEdges(id: Int, group: String): Set<Int> = getOutgoingEdges(id, setOf(group))
    override fun getIncomingEdges(id: Int, group: String): Set<Int> = getIncomingEdges(id, setOf(group))
    override fun getOutgoingEdges(id: Int, groups: Set<String>): Set<Int> =
        getOutgoingEdges(id).filterTo(LinkedHashSet()) { tagGroup(getEdgeStructure(it).tag) in groups }
    override fun getIncomingEdges(id: Int, groups: Set<String>): Set<Int> =
        getIncomingEdges(id).filterTo(LinkedHashSet()) { tagGroup(getEdgeStructure(it).tag) in groups }
    override fun getEdges(src: Int, dst: Int, tag: String?): Set<Int> =
        if (!containsNode(src) || !containsNode(dst)) emptySet() else getOutgoingEdges(src).filterTo(LinkedHashSet()) {
            val edge = getEdgeStructure(it)
            edge.dst == dst && (tag == null || edge.tag == tag)
        }
    override fun findEdge(src: Int, dst: Int): Int? = getEdges(src, dst).firstOrNull()
    override fun findEdge(src: Int, dst: Int, tag: String): Int? = getEdges(src, dst, tag).firstOrNull()
}
