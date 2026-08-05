package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Mutable active layer of [LayeredStorageImpl].
 *
 * Holds columnar node/edge properties, edge endpoints, adjacency sets, and
 * metadata, all keyed by global IDs. Columnar bookkeeping is delegated to
 * [ColumnarProperties].
 */
internal class ActiveLayer {
    val nodeColumns = HashMap<String, HashMap<Int, IValue>>()
    val edgeColumns = HashMap<String, HashMap<Int, IValue>>()
    val edgeEndpoints = HashMap<Int, IStorage.EdgeStructure>()
    val outEdges = HashMap<Int, MutableSet<Int>>()
    val inEdges = HashMap<Int, MutableSet<Int>>()
    val metaProperties = HashMap<String, IValue>()

    val nodeIds: Set<Int> get() = outEdges.keys

    fun containsNode(id: Int): Boolean = id in outEdges

    fun containsEdge(id: Int): Boolean = id in edgeEndpoints

    fun addNode(
        id: Int,
        properties: Map<String, IValue>,
    ) {
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
        for ((key, value) in properties) {
            nodeColumns.getOrPut(key) { HashMap() }[id] = value
        }
    }

    fun addEdge(
        id: Int,
        structure: IStorage.EdgeStructure,
        properties: Map<String, IValue>,
    ) {
        edgeEndpoints[id] = structure
        outEdges.getValue(structure.src).add(id)
        inEdges.getValue(structure.dst).add(id)
        for ((key, value) in properties) {
            edgeColumns.getOrPut(key) { HashMap() }[id] = value
        }
    }

    fun collectNodeProperties(id: Int): Map<String, IValue> = ColumnarProperties.collectProperties(id, nodeColumns)

    fun collectEdgeProperties(id: Int): Map<String, IValue> = ColumnarProperties.collectProperties(id, edgeColumns)

    fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ColumnarProperties.setColumnarProperties(id, properties, nodeColumns)
    }

    fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ColumnarProperties.setColumnarProperties(id, properties, edgeColumns)
    }

    fun removeNode(id: Int) {
        for (eid in (outEdges[id] ?: emptySet<Int>()).toList()) removeEdge(eid)
        for (eid in (inEdges[id] ?: emptySet<Int>()).toList()) removeEdge(eid)
        outEdges.remove(id)
        inEdges.remove(id)
        ColumnarProperties.removeEntityFromColumns(id, nodeColumns)
    }

    fun removeEdge(id: Int) {
        val edge = edgeEndpoints.remove(id) ?: return
        outEdges[edge.src]?.remove(id)
        inEdges[edge.dst]?.remove(id)
        ColumnarProperties.removeEntityFromColumns(id, edgeColumns)
    }

    fun clear() {
        outEdges.clear()
        inEdges.clear()
        edgeEndpoints.clear()
        edgeColumns.clear()
        nodeColumns.clear()
        metaProperties.clear()
    }
}
