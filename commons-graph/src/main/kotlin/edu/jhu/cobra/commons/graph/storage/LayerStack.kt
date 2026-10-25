package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue

/**
 * The two layers of a [LayeredStorageImpl] and the resolution of its reads over them.
 *
 * The active layer is authoritative for the entities it holds; an entity only the frozen layer holds reads from
 * the frozen layer; an edge the active layer tombstoned reads as absent. Not synchronized: the owning storage
 * calls every member under its lock.
 */
internal class LayerStack {
    val active = ActiveLayer()
    var frozen: FrozenLayer? = null

    fun nodeIds(): Set<Int> {
        val frozenIds = frozen?.nodeIds ?: emptySet()
        if (active.nodeIds.isEmpty()) return frozenIds
        if (frozenIds.isEmpty()) return active.nodeIds
        return UnionSet(frozenIds, active.nodeIds)
    }

    fun containsNode(id: Int): Boolean = active.containsNode(id) || frozen?.containsNode(id) == true

    fun nodeProperties(id: Int): Map<String, IValue> {
        if (active.containsNode(id)) return ColumnViewMap(id, active.nodeColumns)
        return frozen?.nodeProperties(id) ?: throw EntityNotExistException(id.toString())
    }

    fun nodeProperty(
        id: Int,
        name: String,
    ): IValue? =
        when {
            active.containsNode(id) -> active.nodeColumns[name]?.get(id)
            frozen?.containsNode(id) == true -> frozen?.nodeProperty(id, name)
            else -> throw EntityNotExistException(id.toString())
        }

    // Writes always land in the active layer; a frozen node is promoted by copying
    // its frozen properties into the active columns first.
    fun promoteNode(id: Int) {
        if (active.containsNode(id)) return
        val props = frozen?.nodeProperties(id) ?: throw EntityNotExistException(id.toString())
        active.addNode(id, props)
    }

    fun edgeIds(): Set<Int> {
        val frozenIds = frozen?.edgeIds?.let(::withoutTombstones) ?: emptySet()
        if (active.edgeEndpoints.isEmpty()) return frozenIds
        if (frozenIds.isEmpty()) return active.edgeEndpoints.keys
        return UnionSet(frozenIds, active.edgeEndpoints.keys)
    }

    fun containsEdge(id: Int): Boolean = active.containsEdge(id) || containsFrozenEdge(id)

    // A frozen edge is visible unless the active layer tombstoned it.
    fun containsFrozenEdge(id: Int): Boolean = frozen?.containsEdge(id) == true && id !in active.deletedEdgeIds

    fun withoutTombstones(frozenEdges: Set<Int>): Set<Int> =
        if (active.deletedEdgeIds.isEmpty()) frozenEdges else ExcludingSet(frozenEdges, active.deletedEdgeIds)

    fun edgeStructure(id: Int): IStorage.EdgeStructure {
        active.edgeEndpoints[id]?.let { return it }
        if (!containsFrozenEdge(id)) throw EntityNotExistException(id.toString())
        return checkNotNull(frozen?.edgeStructure(id))
    }

    fun edgeProperties(id: Int): Map<String, IValue> {
        if (active.containsEdge(id)) return ColumnViewMap(id, active.edgeColumns)
        if (!containsFrozenEdge(id)) throw EntityNotExistException(id.toString())
        return checkNotNull(frozen?.edgeProperties(id))
    }

    // The row one layer itself holds, or null; a tombstone hides an edge from VISIBLE only.
    fun edgeProperties(
        id: Int,
        layer: StorageLayer,
    ): Map<String, IValue>? =
        when (layer) {
            StorageLayer.VISIBLE -> if (containsEdge(id)) edgeProperties(id) else null
            StorageLayer.ACTIVE -> if (active.containsEdge(id)) ColumnViewMap(id, active.edgeColumns) else null
            StorageLayer.FROZEN -> frozen?.edgeProperties(id)
        }

    fun edgeProperty(
        id: Int,
        name: String,
    ): IValue? =
        when {
            active.containsEdge(id) -> active.edgeColumns[name]?.get(id)
            containsFrozenEdge(id) -> frozen?.edgeProperty(id, name)
            else -> throw EntityNotExistException(id.toString())
        }

    fun metaNames(): Set<String> {
        val frozenNames = (frozen?.metaNames ?: emptySet()) - active.deletedMetaNames
        if (active.metaProperties.isEmpty()) return frozenNames
        if (frozenNames.isEmpty()) return active.metaProperties.keys
        return UnionSet(frozenNames, active.metaProperties.keys)
    }

    fun meta(name: String): IValue? {
        active.metaProperties[name]?.let { return it }
        if (name in active.deletedMetaNames) return null
        return frozen?.meta(name)
    }
}
