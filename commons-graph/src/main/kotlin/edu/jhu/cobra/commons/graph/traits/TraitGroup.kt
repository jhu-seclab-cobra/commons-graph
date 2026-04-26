package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal

interface TraitGroup<N : AbcNode, E : AbcEdge> : IGraph<N, E> {
    companion object {
        const val PROP_GROUP = "__group__"
        const val PROP_SUFFIX = "__suffix__"
        const val META_COUNTER_PREFIX = "__grp_cnt_"
        const val META_GLOBAL_COUNTER = "__grp_global_cnt__"
    }

    val groupPrefix: String

    val storage: IStorage

    val groupedNodesCounter: MutableMap<String, Int>

    val suffixIndex: MutableMap<Pair<String, String>, NodeID>

    fun assignGroup(node: N, group: String, suffix: String? = null) {
        require(group.isNotEmpty()) { "Group name cannot be empty" }
        require(group in groupedNodesCounter) { "Group $group not registered" }
        require(suffix == null || suffix.isNotEmpty()) { "Suffix cannot be empty" }
        val counter = groupedNodesCounter.compute(group) { _, v -> v!! + 1 }!!
        storage.setMeta("$META_COUNTER_PREFIX$group", counter.numVal)
        val actualSuffix = suffix ?: counter.toString()
        val key = group to actualSuffix
        if (key in suffixIndex) throw EntityAlreadyExistException("$group:$actualSuffix")
        node[PROP_GROUP] = group.strVal
        node[PROP_SUFFIX] = actualSuffix.strVal
        suffixIndex[key] = node.id
    }

    fun addGroupNode(group: String, suffix: String? = null): N {
        require(group.isNotEmpty()) { "Group name cannot be empty" }
        require(group in groupedNodesCounter) { "Group $group not registered" }
        require(suffix == null || suffix.isNotEmpty()) { "Suffix cannot be empty" }
        val current = (storage.getMeta(META_GLOBAL_COUNTER) as? NumVal)?.toInt() ?: 0
        val next = current + 1
        storage.setMeta(META_GLOBAL_COUNTER, next.numVal)
        val nodeID = "${groupPrefix}_$next"
        val node = addNode(withID = nodeID)
        assignGroup(node, group, suffix)
        return node
    }

    fun addGroupNode(sameGroupNode: AbcNode, suffix: String? = null): N {
        val group = getGroupName(node = sameGroupNode)
        require(group != null) { "Node has no group: ${sameGroupNode.id}" }
        return addGroupNode(group, suffix)
    }

    fun getGroupNode(group: String, suffix: String): N? {
        require(group.isNotEmpty()) { "Group name cannot be empty" }
        require(suffix.isNotEmpty()) { "Suffix cannot be empty" }
        val nodeID = suffixIndex[group to suffix] ?: return null
        return getNode(whoseID = nodeID)
    }

    fun getGroupName(node: AbcNode): String? =
        (node[PROP_GROUP] as? StrVal)?.core

    fun getGroupSuffix(node: AbcNode): String? =
        (node[PROP_SUFFIX] as? StrVal)?.core

    fun getGroupNodes(group: String): Sequence<N> =
        getAllNodes { (it[PROP_GROUP] as? StrVal)?.core == group }

    fun rebuildGroupCaches() {
        groupedNodesCounter.clear()
        suffixIndex.clear()
        for (nodeID in nodeIDs) {
            val node = getNode(whoseID = nodeID) ?: continue
            val group = (node[PROP_GROUP] as? StrVal)?.core ?: continue
            val suffix = (node[PROP_SUFFIX] as? StrVal)?.core ?: continue
            suffixIndex[group to suffix] = nodeID
            val persisted = (storage.getMeta("$META_COUNTER_PREFIX$group") as? NumVal)?.toInt() ?: 0
            groupedNodesCounter[group] = maxOf(groupedNodesCounter[group] ?: 0, persisted)
        }
    }
}
