package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.graph.impl.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

/**
 * Base class for TraitNodeGroup tests providing shared test infrastructure.
 */
abstract class AbcTraitNodeGroupTest {
    protected class TestNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
        enum class Type {
            TEST
        }

        override val type: AbcNode.Type
            get() = object : AbcNode.Type {
                override val name: String get() = Type.TEST.name
            }
    }

    protected class TestEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
        enum class Type {
            TEST
        }

        override val type: AbcEdge.Type
            get() = object : AbcEdge.Type {
                override val name: String get() = Type.TEST.name
            }
    }

    protected class TestGraph : AbcSimpleGraph<TestNode, TestEdge>(), TraitNodeGroup<TestNode, TestEdge> {
        override val storage = NativeStorageImpl()
        override val groupedNodesCounter: MutableMap<String, Int> = mutableMapOf()

        override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
        override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
    }

    protected lateinit var graph: TestGraph

    protected fun registerGroup(group: String) {
        graph.groupedNodesCounter[group] = 0
    }
}

