package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Black-box tests for an edge type whose indexed labels hold labels beyond its stored ones.
 *
 * - `index lists a carrier by its indexed labels` -- a graph built over existing edges indexes the indexed labels
 * - `visit reaches a carrier whose stored labels lack the label` -- verification reads the indexed labels
 * - `restored edges are re-recorded by their indexed labels` -- restoreLabelIndex reads the indexed labels
 * - `an edge type without indexed labels keeps its stored labels` -- the default equals the stored labels
 */
internal class PosetTraitIndexedLabelsTest {
    private lateinit var graph: IndexedGraph
    private val stored = Label("stored")
    private val extra = Label("extra")

    // A graph over storage that already holds the edge builds its index from the indexed labels.
    @BeforeTest
    fun setUp() {
        val storage = NativeStorageImpl()
        val first = IndexedGraph(storage)
        first.addNode("a")
        first.addNode("b")
        first.addEdge("a", "b", "rel", stored)
        graph = IndexedGraph(storage)
        graph.extra["a-rel-b"] = setOf(extra)
    }

    @Test
    fun `index lists a carrier by its indexed labels`() {
        val carriers = graph.getLabeledEdges(extra).toList()

        assertEquals(1, carriers.size)
        assertEquals(setOf(stored), carriers.single().storedLabels)
    }

    @Test
    fun `visit reaches a carrier whose stored labels lack the label`() {
        val visited = mutableListOf<IndexedEdge>()
        graph.visitLabeledEdgesSince(extra) { visited += it; null }

        assertEquals(1, visited.size)
    }

    @Test
    fun `restored edges are re-recorded by their indexed labels`() {
        graph.getLabeledEdges(stored).toList()
        val edge = graph.getEdge("a", "b", "rel")!!
        val later = Label("later")
        graph.extra[edge.id] = setOf(extra, later)

        graph.restoreLabelIndex(setOf(edge.storageId)) { edge }

        assertEquals(1, graph.getLabeledEdges(later).count())
    }

    @Test
    fun `an edge type without indexed labels keeps its stored labels`() {
        val plain = TestGraph()
        plain.addNode("a")
        plain.addNode("b")
        val edge = plain.addEdge("a", "b", "rel", stored)

        assertEquals(edge.storedLabels, edge.indexedLabels)
        assertTrue(plain.getLabeledEdges(extra).none())
    }
}

internal class IndexedEdge(
    private val extra: Map<String, Set<Label>>,
) : AbcEdge() {
    override val type: AbcEdge.Type =
        object : AbcEdge.Type {
            override val name = "XE"
        }

    override val indexedLabels: Set<Label>
        get() = storedLabels + extra[id].orEmpty()
}

internal class IndexedGraph(
    override val storage: IStorage = NativeStorageImpl(),
) : AbcSimpleGraph<TestNode, IndexedEdge>(),
    PosetTrait<TestNode, IndexedEdge> {
    val extra = HashMap<String, Set<Label>>()
    override val graphId: String = "IndexedPoset"
    override val poset: IPoset = PosetDftImpl(NativeStorageImpl())
    override val labelIndex: LabelEdgeIndex<IndexedEdge> = LabelEdgeIndex()

    init {
        rebuild()
    }

    override fun newNodeObj() = TestNode()

    override fun newEdgeObj() = IndexedEdge(extra)

    override fun onRestoredEdges(ids: Set<Int>) = restoreLabelIndex(ids, ::edgeByStorageId)
}
