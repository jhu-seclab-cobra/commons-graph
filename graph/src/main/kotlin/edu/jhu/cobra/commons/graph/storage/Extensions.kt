package edu.jhu.cobra.commons.graph.storage

/**
 * Transfers all data from this storage into [target].
 *
 * Copies all nodes (with properties), all edges (with properties), and all
 * metadata into [target]. Nodes are transferred before edges to satisfy
 * edge source/destination existence constraints.
 *
 * This storage is not modified. [target] must be empty or accept the transferred
 * entities without ID conflicts.
 *
 * @param target The destination storage to receive all data.
 * @throws edu.jhu.cobra.commons.graph.EntityAlreadyExistException if [target] already contains a transferred entity.
 */
fun IStorage.transferTo(target: IStorage) {
    for (nodeId in nodeIDs) {
        target.addNode(nodeId, getNodeProperties(nodeId))
    }
    for (edgeId in edgeIDs) {
        target.addEdge(edgeId, getEdgeProperties(edgeId))
    }
}
