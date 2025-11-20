package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.*

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

/**
 * Retrieves the group name of a node based on its identifier.
 *
 * @return The name of the group to which the node belongs.
 */
fun AbcNode.getGroupName() = id.name.substringAfter("@").substringBeforeLast("#")

/**
 * Converts a map to an array of key-value pairs.
 *
 * Useful for converting property maps to vararg parameters.
 *
 * @return Array containing pairs of keys and values from the map.
 */
fun <K, V> Map<K, V>.toTypeArray(): Array<Pair<K, V>> {
    return Array(this.size) { this.entries.elementAt(it).toPair() }
}