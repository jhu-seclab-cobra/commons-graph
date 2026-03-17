package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

/**
 * Base class for TraitNodeGroup tests providing shared test infrastructure.
 */
abstract class AbcTraitNodeGroupTest {
    protected class TestNode(
        storage: IStorage,
        nodeId: NodeID,
    ) : AbcNode(storage, nodeId) {
        enum class Type {
            TEST,
        }

        override val type: AbcNode.Type
            get() =
                object : AbcNode.Type {
                    override val name: String get() = Type.TEST.name
                }
    }

    protected class TestEdge(
        storage: IStorage,
        edgeId: String,
    ) : AbcEdge(storage, edgeId) {
        enum class Type {
            TEST,
        }

        override val type: AbcEdge.Type
            get() =
                object : AbcEdge.Type {
                    override val name: String get() = Type.TEST.name
                }
    }

    protected class TestGraph :
        AbcSimpleGraph<TestNode, TestEdge>(),
        TraitNodeGroup<TestNode, TestEdge> {
        override val storage = NativeStorageImpl()
        override val posetStorage = NativeStorageImpl()
        override val groupPrefix: String = "Test"
        override val groupedNodesCounter: MutableMap<String, Int> = mutableMapOf()

        override fun newNodeObj(nodeId: NodeID) = TestNode(storage, nodeId)

        override fun newEdgeObj(edgeId: String) =
            TestEdge(storage, edgeId)
    }

    protected lateinit var graph: TestGraph

    protected fun registerGroup(group: String) {
        graph.groupedNodesCounter[group] = 0
    }
}
