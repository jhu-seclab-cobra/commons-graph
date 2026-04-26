package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

internal abstract class AbcTraitGroupTest {
    protected class TestNode : AbcNode() {
        override val type: AbcNode.Type = object : AbcNode.Type {
            override val name: String = "TestNode"
        }
    }

    protected class TestEdge : AbcEdge() {
        override val type: AbcEdge.Type = object : AbcEdge.Type {
            override val name: String = "TestEdge"
        }
    }

    protected class TestGraph :
        AbcSimpleGraph<TestNode, TestEdge>(),
        TraitGroup<TestNode, TestEdge> {
        override val storage = NativeStorageImpl()
        override val graphId: String = "TestNodeGroup"
        override val groupPrefix: String = "Test"
        override val groupedNodesCounter: MutableMap<String, Int> = mutableMapOf()
        override val suffixIndex: MutableMap<Pair<String, String>, String> = mutableMapOf()

        override fun newNodeObj() = TestNode()
        override fun newEdgeObj() = TestEdge()

        fun doRebuild() {
            rebuild()
            rebuildGroupCaches()
        }
    }

    protected lateinit var graph: TestGraph

    protected fun registerGroup(group: String) {
        graph.groupedNodesCounter[group] = 0
    }
}
