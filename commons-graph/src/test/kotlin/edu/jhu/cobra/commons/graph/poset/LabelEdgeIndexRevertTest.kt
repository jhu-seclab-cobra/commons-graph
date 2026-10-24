package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcMultipleGraph
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Black-box tests for the label carrier index across storage reverts (spec-poset.md, Label Carrier Index;
 * spec-graph.md, Revert): after a revert, getLabeledEdges returns exactly the stored carriers whose endpoints
 * belong to the graph, whatever queries ran while the carrier was hidden.
 *
 * - `deleting the last label then reverting restores the carrier` -- tombstone, intervening query pruned it
 * - `deleting the edge with three arguments then reverting restores the carrier` -- ordinary deletion
 * - `deleting with both endpoints promoted then reverting restores the carrier` -- promoted rows and tombstone
 * - `reverting without an intervening query keeps the carrier` -- control
 * - `reverting a removed label of a surviving edge restores the carrier` -- property image restored
 * - `reverting twice without changes keeps the carrier` -- control, empty notice
 * - `clearing the storage leaves no carrier` -- control, removals only
 * - `reverting an edge added after the freeze leaves no carrier` -- control, active-only removal
 * - `a claimed graph loses the carrier when its claims revert` -- ownership marks follow the revert
 * - `a graph regains the carrier when it claims the endpoints again` -- pruning never rests on ownership
 * - `a first index build while the edge is hidden still finds it after the revert` -- cold index
 * - `reverting before the first label operation keeps the carrier` -- unbuilt index
 */
internal class LabelEdgeIndexRevertTest {
    private val label = Label("original")
    private val other = Label("other")

    private fun labeledGraph(
        storage: IStorage,
        graphId: String = "labeled",
    ): LabeledMultiGraph =
        LabeledMultiGraph(storage, graphId).apply {
            addNode("source")
            addNode("destination")
            addEdge("source", "destination", "relation", label)
        }

    // Label query counts around a delete and a revert: before, while deleted, after, and outgoing edges after.
    private fun revertCounts(
        storage: LayeredStorageImpl,
        delete: (LabeledMultiGraph) -> Unit,
    ): List<Int> {
        val graph = labeledGraph(storage)
        val counts = mutableListOf(graph.getLabeledEdges(label).count())
        storage.freeze()
        delete(graph)
        counts += graph.getLabeledEdges(label).count()
        storage.clearActiveLayer()
        counts += graph.getLabeledEdges(label).count()
        counts += graph.getOutgoingEdges("source").count()
        return counts
    }

    @Test
    fun `deleting the last label then reverting restores the carrier`() {
        val counts = revertCounts(LayeredStorageImpl()) { it.delEdge("source", "destination", "relation", label) }

        assertEquals(listOf(1, 0, 1, 1), counts)
    }

    @Test
    fun `deleting the edge with three arguments then reverting restores the carrier`() {
        val counts = revertCounts(LayeredStorageImpl()) { it.delEdge("source", "destination", "relation") }

        assertEquals(listOf(1, 0, 1, 1), counts)
    }

    @Test
    fun `deleting with both endpoints promoted then reverting restores the carrier`() {
        val counts =
            revertCounts(LayeredStorageImpl()) {
                it.getNode("source")!!["property"] = "active".strVal
                it.getNode("destination")!!["property"] = "active".strVal
                it.delEdge("source", "destination", "relation")
            }

        assertEquals(listOf(1, 0, 1, 1), counts)
    }

    @Test
    fun `reverting without an intervening query keeps the carrier`() {
        val storage = LayeredStorageImpl()
        val graph = labeledGraph(storage)
        storage.freeze()
        graph.delEdge("source", "destination", "relation", label)

        storage.clearActiveLayer()

        assertEquals(1, graph.getLabeledEdges(label).count())
    }

    @Test
    fun `reverting a removed label of a surviving edge restores the carrier`() {
        val storage = LayeredStorageImpl()
        val graph = labeledGraph(storage)
        graph.addEdge("source", "destination", "relation", other)
        storage.freeze()
        val counts = mutableListOf(graph.getLabeledEdges(label).count())
        graph.delEdge("source", "destination", "relation", label)
        counts += graph.getLabeledEdges(label).count()

        storage.clearActiveLayer()
        counts += graph.getLabeledEdges(label).count()

        assertEquals(listOf(1, 0, 1), counts)
    }

    @Test
    fun `reverting twice without changes keeps the carrier`() {
        val storage = LayeredStorageImpl()
        val graph = labeledGraph(storage)
        storage.freeze()

        storage.clearActiveLayer()
        storage.clearActiveLayer()

        assertEquals(1, graph.getLabeledEdges(label).count())
    }

    @Test
    fun `clearing the storage leaves no carrier`() {
        val storage = LayeredStorageImpl()
        val graph = labeledGraph(storage)
        storage.freeze()

        storage.clear()

        assertEquals(listOf(0, 0), listOf(graph.getLabeledEdges(label).count(), graph.nodeIDs.size))
    }

    @Test
    fun `reverting an edge added after the freeze leaves no carrier`() {
        val storage = LayeredStorageImpl()
        storage.freeze()
        val graph = labeledGraph(storage)

        storage.clearActiveLayer()

        assertEquals(listOf(0, 0), listOf(graph.getLabeledEdges(label).count(), graph.nodeIDs.size))
    }

    @Test
    fun `a claimed graph loses the carrier when its claims revert`() {
        val storage = LayeredStorageImpl()
        val owner = labeledGraph(storage)
        storage.freeze()
        val claimant = LabeledMultiGraph(storage, "claimant")
        claimant.claimNode(owner.getNode("source")!!)
        claimant.claimNode(owner.getNode("destination")!!)
        val counts = mutableListOf(claimant.getLabeledEdges(label).count())

        storage.clearActiveLayer()
        counts += claimant.getLabeledEdges(label).count()
        counts += claimant.getAllEdges().count()
        counts += claimant.nodeIDs.size

        assertEquals(listOf(1, 0, 0, 0), counts)
        assertEquals(1, owner.getLabeledEdges(label).count())
    }

    @Test
    fun `a graph regains the carrier when it claims the endpoints again`() {
        val storage = LayeredStorageImpl()
        val owner = labeledGraph(storage)
        storage.freeze()
        val claimant = LabeledMultiGraph(storage, "claimant")
        claimant.claimNode(owner.getNode("source")!!)
        claimant.claimNode(owner.getNode("destination")!!)
        claimant.getLabeledEdges(label).count()
        storage.clearActiveLayer()
        claimant.getLabeledEdges(label).count()

        claimant.claimNode(owner.getNode("source")!!)
        claimant.claimNode(owner.getNode("destination")!!)

        assertEquals(1, claimant.getLabeledEdges(label).count())
    }

    @Test
    fun `a first index build while the edge is hidden still finds it after the revert`() {
        val storage = LayeredStorageImpl()
        val graph = LabeledMultiGraph(storage, "labeled")
        graph.addNode("source")
        graph.addNode("destination")
        graph.addEdge("source", "destination", "relation").labels = setOf(label)
        storage.freeze()
        graph.delEdge("source", "destination", "relation")
        val counts = mutableListOf(graph.getLabeledEdges(label).count())

        storage.clearActiveLayer()
        counts += graph.getLabeledEdges(label).count()

        assertEquals(listOf(0, 1), counts)
    }

    @Test
    fun `reverting before the first label operation keeps the carrier`() {
        val storage = LayeredStorageImpl()
        val graph = LabeledMultiGraph(storage, "labeled")
        graph.addNode("source")
        graph.addNode("destination")
        graph.addEdge("source", "destination", "relation").labels = setOf(label)
        storage.freeze()
        graph.delEdge("source", "destination", "relation")

        storage.clearActiveLayer()

        assertEquals(1, graph.getLabeledEdges(label).count())
    }
}

/** A multi-edge graph with a label index, over [storage], whose revert restoration records the labels. */
internal class LabeledMultiGraph(
    override val storage: IStorage,
    override val graphId: String,
) : AbcMultipleGraph<TestNode, TestEdge>(),
    PosetTrait<TestNode, TestEdge> {
    override val poset: IPoset = PosetDftImpl(NativeStorageImpl())
    override val labelIndex: LabelEdgeIndex<TestEdge> = LabelEdgeIndex()

    override fun newNodeObj() = TestNode()

    override fun newEdgeObj() = TestEdge()

    override fun onRestoredEdges(ids: Set<Int>) = restoreLabelIndex(ids, ::edgeByStorageId)
}
