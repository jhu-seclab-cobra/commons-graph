package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.getMeta
import edu.jhu.cobra.commons.graph.setMeta
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.numVal

interface TraitNodeGroup<N : AbcNode, E: AbcEdge>: IGraph<N, E> {


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
    fun addGroupRoot(group: String): N =
        addNode(NodeID("$graphName@$group#0"))

    /**
     * Adds a node that is part of a group, optionally with a suffix to distinguish it.
     *
     * @param group The name of the group to which the node belongs. Defaults to "UNKNOWN".
     * @param suffix An optional suffix to uniquely identify the node within the group.
     * @return The newly added grouped node.
     */
    fun addGroupNode(group: String = "UNKNOWN", suffix: String? = null): N {
        val nodePrefix = "$graphName@$group"
        if (suffix != null) return addNode(NodeID("$nodePrefix#$suffix"))
        do {
            val nodeID = NodeID("$nodePrefix#${increase(group)}")
            if (getNode(nodeID) == null) return addNode(nodeID)
        } while (true)
    }

    /**
     * Adds a new node to the same group as an existing node.
     *
     * @param sameGroupNode The node from which the group identifier is extracted.
     * @param suffix An optional suffix to uniquely identify the new node within the group.
     * @return The newly added node associated with the same group.
     */
    fun addGroupNode(sameGroupNode: AbcNode, suffix: String? = null) =
        addGroupNode(sameGroupNode.id.name.substringAfter("@").substringBeforeLast("#"), suffix)

    /**
     * Retrieves the root node of a group based on its name.
     *
     * @param groupName The name of the group.
     * @return The group root node if it exists, or `null` otherwise.
     */
    fun getGroupRoot(groupName: String): N? =
        getNode(NodeID("$graphName@$groupName#0"))

    /**
     * Retrieves the root node of the group to which a specified member node belongs.
     *
     * @param ofMember The member node whose group root is being queried.
     * @return The root node of the group if it exists, or `null` otherwise.
     */
    fun getGroupRoot(ofMember: AbcNode): N? {
        val groupPrefix = ofMember.id.name.substringBefore("#")
        return getNode(whoseID = NodeID("$groupPrefix#0"))
    }

    /**
     * Retrieves a node that is part of a group and identified by a unique suffix.
     *
     * @param groupName The name of the group.
     * @param suffix The suffix that identifies the node within the group.
     * @return The grouped node if it exists, or `null` otherwise.
     */
    fun getGroupNode(groupName: String, suffix: String): N? =
        getNode(NodeID("$graphName@$groupName#${suffix}"))

    /**
     * Retrieves the group name of a node based on its identifier.
     *
     * @return The name of the group to which the node belongs.
     */
    fun getGroupName(node: N) = node.id.name.substringAfter("@").substringBeforeLast("#")

}