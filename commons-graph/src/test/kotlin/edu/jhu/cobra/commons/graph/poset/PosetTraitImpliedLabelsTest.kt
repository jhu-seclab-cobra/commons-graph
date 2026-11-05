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
 * Black-box tests for an edge type whose labels include labels its graph implies beyond the stored ones.
 *
 * - `query filters by the implied labels` -- visibility reads AbcEdge.labels
 * - `label index lists only stored carriers` -- getLabeledEdges and visitLabeledEdgesSince read the stored labels
 * - `delEdge of an implied label keeps the edge and its stored labels` -- writers read the stored labels
 */
internal class PosetTraitImpliedLabelsTest {
    private lateinit var graph: ImpliedGraph
    private val stored = Label("stored")
    private val implied = Label("implied")

    @BeforeTest
    fun setUp() {
        graph = ImpliedGraph()
        graph.addNode("a")
        graph.addNode("b")
        val edge = graph.addEdge("a", "b", "rel", stored)
        graph.implied[edge.id] = setOf(implied)
    }

    @Test
    fun `query filters by the implied labels`() {
        val edges = graph.getOutgoingEdges("a", implied).toList()

        assertEquals(1, edges.size)
        assertEquals(setOf(stored, implied), edges.single().labels)
    }

    @Test
    fun `label index lists only stored carriers`() {
        val visited = mutableListOf<ImpliedEdge>()
        graph.visitLabeledEdgesSince(implied) { visited += it; null }

        assertTrue(graph.getLabeledEdges(implied).none())
        assertTrue(visited.isEmpty())
        assertEquals(1, graph.getLabeledEdges(stored).count())
    }

    @Test
    fun `delEdge of an implied label keeps the edge and its stored labels`() {
        graph.delEdge("a", "b", "rel", implied)

        val edge = graph.getEdge("a", "b", "rel")
        assertEquals(setOf(stored), edge?.storedLabels)
    }
}

internal class ImpliedEdge(
    private val implied: Map<String, Set<Label>>,
) : AbcEdge() {
    override val type: AbcEdge.Type =
        object : AbcEdge.Type {
            override val name = "IE"
        }

    override val labels: Set<Label>
        get() = storedLabels + implied[id].orEmpty()
}

internal class ImpliedGraph(
    override val storage: IStorage = NativeStorageImpl(),
) : AbcSimpleGraph<TestNode, ImpliedEdge>(),
    PosetTrait<TestNode, ImpliedEdge> {
    val implied = HashMap<String, Set<Label>>()
    override val graphId: String = "ImpliedPoset"
    override val poset: IPoset = PosetDftImpl(NativeStorageImpl())
    override val labelIndex: LabelEdgeIndex<ImpliedEdge> = LabelEdgeIndex()

    override fun newNodeObj() = TestNode()

    override fun newEdgeObj() = ImpliedEdge(implied)

    override fun onRestoredEdges(ids: Set<Int>) = restoreLabelIndex(ids, ::edgeByStorageId)
}
