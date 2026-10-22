package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.storage.IRevertibleStorage
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

// Shared fixtures for poset tests: a simple graph with PosetTrait backed by PosetDftImpl.

internal class TestNode : AbcNode() {
    override val type: AbcNode.Type =
        object : AbcNode.Type {
            override val name = "TN"
        }
}

internal class TestEdge : AbcEdge() {
    override val type: AbcEdge.Type =
        object : AbcEdge.Type {
            override val name = "TE"
        }
}

internal class TestGraph(
    override val storage: IStorage = NativeStorageImpl(),
) : AbcSimpleGraph<TestNode, TestEdge>(),
    PosetTrait<TestNode, TestEdge> {
    override val graphId: String = "TestPoset"
    val posetStorage = NativeStorageImpl()
    override val poset: IPoset = PosetDftImpl(posetStorage)
    override val labelIndex: LabelEdgeIndex<TestEdge> = LabelEdgeIndex()

    override fun newNodeObj() = TestNode()

    override fun newEdgeObj() = TestEdge()
}

/**
 * A [LayeredStorageImpl] that counts [getOutgoingEdges] calls, so a test can tell a
 * closure lookup (no storage read) from a closure rebuild (one read per label).
 */
internal class CountingLayeredStorage(
    val layered: LayeredStorageImpl = LayeredStorageImpl(),
) : IRevertibleStorage by layered {
    var outgoingReads = 0
        private set

    override fun getOutgoingEdges(id: Int): Set<Int> {
        outgoingReads++
        return layered.getOutgoingEdges(id)
    }

    /** Returns how many outgoing-edge reads [action] made. */
    fun readsDuring(action: () -> Unit): Int {
        val before = outgoingReads
        action()
        return outgoingReads - before
    }
}
