package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal

/**
 * Converts the string representation of a [StrVal] to an entity identifier of the specified type.
 *
 * The method uses the type parameter `ID` to determine which specific implementation of `IEntity.ID` to construct,
 * such as `NodeID` or `EdgeID`. The string should conform to the expected format of the corresponding `ID` type.
 *
 * @return The constructed entity identifier of type `ID`.
 * @throws IllegalArgumentException if the specified `ID` type is unsupported.
 */
@Suppress("UNCHECKED_CAST")
fun <K : IEntity.ID> IValue.toEntityID(): K = when (this) {
    is StrVal -> NodeID(this) as K
    is ListVal -> EdgeID(this) as K
    else -> throw IllegalArgumentException("Unsupported ID type")
}

/**
 * The identifier for the meta-node of the graph.
 * The meta-node is used to store metadata about the graph.
 */
val <N : AbcNode, E : AbcEdge> IGraph<N, E>.META_NID get() = NodeID(this.graphName + "__meta__")

/**
 * Retrieves the value of a property from the meta-node of the graph.
 * If the meta-node does not exist, it is created before fetching the property value.
 *
 * @param propName The name of the property to retrieve from the meta-node.
 * @return The value of the specified property if it exists, or `null` otherwise.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getMeta(propName: String): IValue? =
    (getNode(META_NID) ?: addNode(META_NID)).getProp(name = propName)

/**
 * Sets a metadata property on the graph's meta-node.
 * If the meta-node does not exist, it will be created.
 *
 * @param propName The name of the property to set.
 * @param value The value to associate with the specified property.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.setMeta(propName: String, value: IValue?) =
    (getNode(META_NID) ?: addNode(META_NID)).setProp(name = propName, value = value)

/**
 * Retrieves a node from the graph based on its identifier.
 *
 * @param id The unique identifier of the node to retrieve.
 * @return The node associated with the given identifier, or `null` if no such node exists.
 */
operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.get(id: NodeID): N? = getNode(id)

/**
 * Retrieves an edge from the graph based on its identifier.
 *
 * @param id The unique identifier of the edge to retrieve.
 * @return The edge associated with the given identifier, or `null` if no such edge exists.
 */
operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.get(id: EdgeID): E? = getEdge(id)

/**
 * Checks if the graph contains an entity with the specified identifier.
 *
 * @param id The identifier to check for existence in the graph.
 * @return `true` if the graph contains an entity with the specified identifier, `false` otherwise.
 */
operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.contains(id: IEntity.ID) = when (id) {
    is NodeID -> getNode(id) != null
    is EdgeID -> getEdge(id) != null
}

/**
 * Checks if the graph contains the specified entity.
 *
 * @param entity The entity to check for existence in the graph.
 * @return `true` if the graph contains the specified entity, `false` otherwise.
 */
operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.contains(entity: IEntity) = when (entity) {
    is AbcNode -> containNode(entity)
    is AbcEdge -> containEdge(entity)
}

/**
 * Wraps a generic [AbcNode] into its specific graph-context type [N].
 *
 * @param node The generic [AbcNode] to be wrapped into the specific node type [N].
 * @return The node converted to the specific type [N].
 */
infix fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.wrap(node: AbcNode): N = wrapNode(node)

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
    addNode(NodeID("$graphName@$group#0"))

/**
 * Adds a node that is part of a group, optionally with a suffix to distinguish it.
 *
 * @param group The name of the group to which the node belongs. Defaults to "UNKNOWN".
 * @param suffix An optional suffix to uniquely identify the node within the group.
 * @return The newly added grouped node.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.addGroupNode(group: String = "UNKNOWN", suffix: String? = null): N {
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
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.addGroupNode(sameGroupNode: AbcNode, suffix: String? = null) =
    addGroupNode(sameGroupNode.id.name.substringAfter("@").substringBeforeLast("#"), suffix)

/**
 * Retrieves the root node of a group based on its name.
 *
 * @param groupName The name of the group.
 * @return The group root node if it exists, or `null` otherwise.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getGroupRoot(groupName: String): N? =
    getNode(NodeID("$graphName@$groupName#0"))

/**
 * Retrieves the root node of the group to which a specified member node belongs.
 *
 * @param ofMember The member node whose group root is being queried.
 * @return The root node of the group if it exists, or `null` otherwise.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getGroupRoot(ofMember: AbcNode): N? {
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
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getGroupNode(groupName: String, suffix: String): N? =
    getNode(NodeID("$graphName@$groupName#${suffix}"))

/**
 * Retrieves the group name of a node based on its identifier.
 *
 * @param node The node whose group name is to be retrieved.
 * @return The name of the group to which the node belongs.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getGroupName(node: AbcNode) =
    node.id.name.substringAfter("@").substringBeforeLast("#")
