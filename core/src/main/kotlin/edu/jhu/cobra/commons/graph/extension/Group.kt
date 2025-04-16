package edu.jhu.cobra.commons.graph.extension

import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.AbcNode
import edu.jhu.cobra.commons.graph.entity.toNid
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.numVal

private fun IGraph<*, *>.increase(group: String): Int {
    val cntPropName = "${group}_cnt"
    val prevCount = (getMeta(cntPropName) as? NumVal)?.toInt()
    val newCount = prevCount?.inc() ?: 1
    setMeta(cntPropName, newCount.numVal)
    return newCount
}

/**
 * Adds a root node for a group identified by a unique name.
 *
 * @param group The name of the group.
 * @return The newly created group root node.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.addGroupRoot(group: String): N =
    addNode("$graphName@$group#0".toNid)

/**
 * Adds a node that is part of a group, optionally with a suffix to distinguish it.
 *
 * @param group Optional name of the group to which the node belongs.
 * @param suffix Optional suffix to uniquely identify the node within the group.
 * @return The newly added grouped node.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.addGroupNode(group: String = "UNKNOWN", suffix: String? = null): N {
    val nodePrefix = "$graphName@$group"
    if (suffix != null) return addNode("$nodePrefix#$suffix".toNid)
    do {
        val nodeID = "$nodePrefix#${increase(group)}".toNid
        if (getNode(nodeID) == null) return addNode(nodeID)
    } while (true)
}

/**
 * Adds a new node to the same group as an existing node, optionally appending a suffix to its identifier.
 *
 * @param sameGroupNode The node from which the group identifier is extracted.
 * @param suffix An optional suffix to uniquely identify the new node within the group.
 * @return The newly added node associated with the same group.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.addGroupNode(sameGroupNode: AbcNode, suffix: String? = null) =
    addGroupNode(sameGroupNode.id.name.substringAfter("@").substringBeforeLast("#"), suffix)

/**
 * Retrieves the root node of a group based on its name.
 *
 * @param groupName The name of the group.
 * @return The group root node if it exists.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getGroupRoot(groupName: String): N? =
    getNode("$graphName@$groupName#0".toNid)

/**
 * Retrieves the root node of the group to which a specified member node belongs.
 *
 * @param ofMember The member node whose group root is being queried.
 * @return The root node of the group if it exists, otherwise null.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getGroupRoot(ofMember: AbcNode): N? {
    val groupPrefix = ofMember.id.name.substringBefore("#")
    return getNode(whoseID = "$groupPrefix#0".toNid)
}


/**
 * Retrieves a node that is part of a group and identified by a unique suffix.
 *
 * @param groupName The name of the group.
 * @param suffix The suffix that identifies the node within the group.
 * @return The grouped node if it exists.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getGroupNode(groupName: String, suffix: String): N? =
    getNode("$graphName@$groupName#${suffix}".toNid)

/**
 * Retrieves the group name of a node based on its identifier.
 *
 * @param node The node to check.
 * @return True if the node belongs to the specified group, otherwise false.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getGroupName(node: AbcNode) =
    node.id.name.substringAfter("@").substringBeforeLast("#")
