package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

/**
 * Base class for TraitNodeGroup tests providing shared test infrastructure.
 *
 * Provides TestNode, TestEdge, TestGraph (AbcSimpleGraph + TraitNodeGroup),
 * and a helper to register groups.
 */
internal abstract class AbcTraitNodeGroupTest {
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
        TraitNodeGroup<TestNode, TestEdge> {
        override val storage = NativeStorageImpl()
        override val posetStorage = NativeStorageImpl()
        override val groupPrefix: String = "Test"
        override val groupedNodesCounter: MutableMap<String, Int> = mutableMapOf()

        override fun newNodeObj() = TestNode()

        override fun newEdgeObj() = TestEdge()
    }

    protected lateinit var graph: TestGraph

    protected fun registerGroup(group: String) {
        graph.groupedNodesCounter[group] = 0
    }
}
