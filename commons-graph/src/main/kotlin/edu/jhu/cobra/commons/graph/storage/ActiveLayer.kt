package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Mutable active layer of [LayeredStorageImpl].
 *
 * Holds columnar node/edge properties, edge endpoints, adjacency sets,
 * metadata, and edge/metadata tombstones, all keyed by global IDs. Columnar bookkeeping is delegated to
 * [ColumnarProperties].
 */
internal class ActiveLayer(private val families: () -> EdgePropertyFamilies? = { null }) {
    val nodeColumns = HashMap<String, HashMap<Int, IValue>>()
    val edgeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Explicit writes to edges inherited from the frozen layer. Null masks a frozen property.
    val promotedEdgeEdits = HashMap<Int, MutableMap<String, IValue?>>()
    val edgeEndpoints = HashMap<Int, IStorage.EdgeStructure>()
    val outEdges = HashMap<Int, MutableSet<Int>>()
    val inEdges = HashMap<Int, MutableSet<Int>>()
    val metaProperties = HashMap<String, IValue>()

    // Tombstones masking frozen-layer metadata deleted through the active layer.
    val deletedMetaNames = HashSet<String>()

    // Tombstones masking frozen-layer edges deleted through the active layer.
    val deletedEdgeIds = HashSet<Int>()

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

    fun composePromotedEdgeProperties(
        id: Int,
        frozen: Map<String, IValue>,
    ): Map<String, IValue> {
        val edits = promotedEdgeEdits.getValue(id)
        val tag = edgeEndpoints.getValue(id).tag
        val family = families()
        val result = HashMap<String, IValue>()
        for ((name, value) in frozen) {
            val anchor = family.anchor(tag, name)
            if (anchor == null || anchor !in edits) result[name] = value
        }
        for ((name, value) in edits) {
            if (value == null) result.remove(name) else result[name] = value
        }
        return result
    }

    fun hasEdgeProperty(id: Int, name: String): Boolean =
        promotedEdgeEdits[id]?.containsKey(name) ?: (edgeColumns[name]?.containsKey(id) == true)

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
        val promoted = promotedEdgeEdits[id]
        if (promoted == null) {
            ColumnarProperties.setColumnarProperties(id, properties, edgeColumns)
        } else {
            promoted.putAll(properties)
        }
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
        promotedEdgeEdits.remove(id)
    }

    fun clear() {
        outEdges.clear()
        inEdges.clear()
        edgeEndpoints.clear()
        edgeColumns.clear()
        promotedEdgeEdits.clear()
        nodeColumns.clear()
        metaProperties.clear()
        deletedMetaNames.clear()
        deletedEdgeIds.clear()
    }
}
