package edu.jhu.cobra.commons.graph.extension

import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.entity.*
import edu.jhu.cobra.commons.value.IValue

val <N : AbcNode, E : AbcEdge> IGraph<N, E>.META_NID get() = NodeID(this.graphName + "__meta__")

/**
 * Retrieves the value of a property from the meta node of the graph. If the meta node does not exist,
 * it is created before fetching the property value.
 *
 * @param propName The name of the property to retrieve from the meta node.
 * @return The value of the specified property if it exists, or null otherwise.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getMeta(propName: String): IValue? =
    (getNode(META_NID) ?: addNode(META_NID)).getProp(name = propName)

/**
 * Sets a metadata property on the graph's meta node. If the meta node does not exist, it will be created.
 *
 * @param propName The name of the property to set.
 * @param value The value to associate with the specified property.
 */
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.setMeta(propName: String, value: IValue?) =
    (getNode(META_NID) ?: addNode(META_NID)).setProp(name = propName, value = value)

/**
 * Retrieves a node from the graph based on its identifier.
 * This operator allows for direct access to nodes using their unique identifiers,
 * simplifying the syntax for accessing nodes.
 *
 * @param id The unique identifier of the node to retrieve.
 * @return The node associated with the given identifier, or `null` if no such node exists.
 */
operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.get(id: NodeID): N? = getNode(id)

/**
 * Retrieves an edge from the graph based on its identifier.
 * This operator allows for direct access to edges using their unique identifiers,
 * simplifying the syntax for accessing edges.
 *
 * @param id The unique identifier of the edge to retrieve.
 * @return The edge associated with the given identifier, or `null` if no such edge exists.
 */
operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.get(id: EdgeID): E? = getEdge(id)


operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.contains(id: IEntity.ID) = when (id) {
    is NodeID -> getNode(id) != null
    is EdgeID -> getEdge(id) != null
}

/**
 * Checks if the given ID is present in the graph.
 *
 * @param id The ID to check.
 * @return true if the ID is present in the graph, false otherwise.
 */
operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.contains(entity: IEntity) = when (entity) {
    is AbcNode -> containNode(entity)
    is AbcEdge -> containEdge(entity)
}


/**
 * Wraps a generic [AbcNode] into its specific graph-context type [N].
 * This infix function provides a concise way to convert or adapt a generic node
 * into a specific type as defined by the graph's node type parameter [N].
 * The function is useful for graph operations that require node-specific functionalities
 * which might not be available or directly applicable in the generic [AbcNode] class.
 *
 * @param node The generic [AbcNode] to be wrapped into the specific node type [N].
 * @return Returns the node converted to the specific type [N].
 */
infix fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.wrap(node: AbcNode): N = wrapNode(node)

