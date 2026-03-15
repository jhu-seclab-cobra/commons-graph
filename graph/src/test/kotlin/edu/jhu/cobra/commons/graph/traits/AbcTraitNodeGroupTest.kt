package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.InternalID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

/**
 * Base class for TraitNodeGroup tests providing shared test infrastructure.
 */
abstract class AbcTraitNodeGroupTest {
    protected class TestNode(
        storage: IStorage,
        internalId: InternalID,
    ) : AbcNode(storage, internalId) {
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
        internalId: InternalID,
        nodeIdResolver: (InternalID) -> NodeID,
    ) : AbcEdge(storage, internalId, nodeIdResolver) {
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

        override fun newNodeObj(internalId: InternalID) = TestNode(storage, internalId)

        override fun newEdgeObj(internalId: InternalID, nodeIdResolver: (InternalID) -> NodeID) =
            TestEdge(storage, internalId, nodeIdResolver)
    }

    protected lateinit var graph: TestGraph

    protected fun registerGroup(group: String) {
        graph.groupedNodesCounter[group] = 0
    }
}
