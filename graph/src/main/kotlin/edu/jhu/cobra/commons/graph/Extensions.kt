package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.*

/**
 * Converts an [IValue] to an entity identifier of the specified type.
 *
 * @return The constructed entity identifier of type [K].
 * @throws IllegalArgumentException if the value type is unsupported.
 */
@Suppress("UNCHECKED_CAST")
fun <K : IEntity.ID> IValue.toEntityID(): K = when (this) {
    is StrVal -> NodeID(this) as K
    is ListVal -> EdgeID(this) as K
    else -> throw IllegalArgumentException("Unsupported ID type")
}

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
    is NodeID -> containNode(id)
    is EdgeID -> containEdge(id)
}

/**
 * Checks if the graph contains the specified entity.
 *
 * @param entity The entity to check for existence in the graph.
 * @return `true` if the graph contains the specified entity, `false` otherwise.
 */
operator fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.contains(entity: IEntity) = when (entity) {
    is AbcNode -> containNode(entity.id)
    is AbcEdge -> containEdge(entity.id)
}
