package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.NodeID

interface TraitNodeGroup<N : AbcNode, E: AbcEdge>: IGraph<N, E> {

    companion object {
        private const val PREFIX_SPLITTER = '@'
        private const val SUFFIX_SPLITTER = '#'
    }

    /**
     * Monotonically increasing counter for auto-generating node suffixes within each group.
     *
     * Maps group names to the next available suffix value.
     * Counter values only increase and never decrease, even when nodes are deleted.
     * This ensures O(1) node creation performance and avoids expensive existence checks.
     */
    val groupedNodesCounter: MutableMap<String, Int>

    private fun groupNodeID(group: String, suffix: String): NodeID {
        require(group.isNotEmpty()) { "Group name cannot be empty" }
        require(PREFIX_SPLITTER !in group) { "Group name cannot contain '@' character" }
        require(SUFFIX_SPLITTER !in group) { "Group name cannot contain '#' character" }
        require(suffix.isNotEmpty()) { "Suffix cannot be empty" }
        require(SUFFIX_SPLITTER !in suffix) { "Suffix cannot contain '#' character" }
        val cleanGraphName = graphName.replace("@", "")
        return NodeID("$cleanGraphName@$group#$suffix")
    }

    /**
     * Retrieves the group name of a node based on its identifier.
     *
     * Extracts the group name from node ID format: `graphName@groupName#suffix`.
     * Returns null if the node ID does not match the required format.
     *
     * @param node The node whose group name is to be retrieved.
     * @return The name of the group to which the node belongs, or null if the format is invalid.
     */
    fun getGroupName(node: AbcNode): String? {
        val nodeName = node.id.name
        val atIndex = nodeName.indexOf('@')
        val hashIndex = nodeName.indexOf('#')
        
        if (atIndex == -1 || atIndex == 0 ) return null
        if (hashIndex == -1 || atIndex + 1 >= hashIndex) return null

        val groupName = nodeName.substring(atIndex + 1, hashIndex)
        if (groupName.isEmpty()) return null
        
        return groupName
    }

    /**
     * Adds a node that is part of a group, optionally with a suffix to distinguish it.
     *
     * Auto-generated suffixes use a monotonically increasing counter.
     * The counter never decreases, ensuring O(1) performance even after node deletions.
     * This may result in gaps in the numeric sequence, which is expected behavior.
     *
     * @param group The name of the group to which the node belongs. Defaults to "UNKNOWN".
     * @param suffix An optional suffix to uniquely identify the node within the group.
     * @return The newly added grouped node.
     * @throws IllegalArgumentException If the group name or suffix is invalid.
     * @throws edu.jhu.cobra.commons.graph.EntityAlreadyExistException If a node with the generated ID already exists.
     */
    fun addGroupNode(group: String, suffix: String? = null): N {
        require(group in groupedNodesCounter) { "Group $group should exist" }
        val counter = groupedNodesCounter.compute(group) { _, v -> v!! + 1 }
        val groupedNodeID = groupNodeID(group, suffix ?: counter.toString())
        return addNode(whoseID = groupedNodeID)
    }

    /**
     * Adds a new node to the same group as an existing node.
     *
     * @param sameGroupNode The node from which the group identifier is extracted.
     * @param suffix An optional suffix to uniquely identify the new node within the group.
     * @return The newly added node associated with the same group.
     * @throws IllegalArgumentException If the node ID format is invalid or the group does not exist.
     */
    fun addGroupNode(sameGroupNode: AbcNode, suffix: String? = null): N {
        val group = getGroupName(node = sameGroupNode)
        require(group != null) { "Node ID format is invalid: ${sameGroupNode.id.name}" }
        return addGroupNode(group, suffix)
    }

    /**
     * Retrieves a node that is part of a group and identified by a unique suffix.
     *
     * @param group The name of the group.
     * @param suffix The suffix that identifies the node within the group.
     * @return The grouped node if it exists, or `null` otherwise.
     * @throws IllegalArgumentException If the group name or suffix is invalid.
     */
    fun getGroupNode(group: String, suffix: String): N? {
        val groupedNodeID = groupNodeID(group, suffix)
        return getNode(whoseID = groupedNodeID)
    }

}