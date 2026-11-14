package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/** Read-only operations valid on the entering thread during one [GraphReader.read] callback. */
public class StorageReadScope internal constructor(
    private val storage: LayeredStorage,
    private val lifetime: StorageScope,
    private val typed: LayeredStorageImpl? = null,
) {
    /** Required primitive field; absence is an error rather than a zero default. */
    public fun getNodeInt(id: Int, key: PropertyKey<Int>): Int = checkNotNull(getNodeProperty(id, key))

    /** Required primitive field; absence is an error rather than a zero default. */
    public fun getNodeLong(id: Int, key: PropertyKey<Long>): Long = checkNotNull(getNodeProperty(id, key))

    /** Required primitive field in the selected layer. */
    public fun getEdgeInt(id: Int, key: PropertyKey<Int>, layer: StorageLayer = StorageLayer.VISIBLE): Int =
        checkNotNull(getEdgeProperty(id, key, layer))

    /** Required primitive field in the selected layer. */
    public fun getEdgeLong(id: Int, key: PropertyKey<Long>, layer: StorageLayer = StorageLayer.VISIBLE): Long =
        checkNotNull(getEdgeProperty(id, key, layer))

    /** Fixed named node contents, sharing admitted immutable payloads. */
    public fun getNodeContents(id: Int): Map<String, PropertyValue> = access { registered().nodeContents(id) }

    /** One named node content without a legacy codec cycle. */
    public fun getNodeContent(id: Int, name: String): PropertyValue? = access { registered().nodeContent(id, name) }

    /** Fixed contents of one physical edge image. */
    public fun getEdgeContents(id: Int, layer: StorageLayer = StorageLayer.VISIBLE): Map<String, PropertyValue>? =
        access { registered().edgeContents(id, layer) }

    /** One field or indexed cell in the selected physical image. */
    public fun getEdgeContent(id: Int, name: String, layer: StorageLayer = StorageLayer.VISIBLE): PropertyValue? =
        access { registered().edgeContent(id, name, layer) }

    /** Returns an admitted immutable field without a compatibility conversion or payload copy. */
    public fun <T : Any> getNodeProperty(id: Int, key: PropertyKey<T>): T? = access {
        registered().nodeField(id, key)
    }

    /** Selects an immutable field from one physical edge image. */
    public fun <T : Any> getEdgeProperty(
        id: Int,
        key: PropertyKey<T>,
        layer: StorageLayer = StorageLayer.VISIBLE,
    ): T? = access { registered().edgeField(id, key, layer) }

    /** Borrows pair-addressed cells; immutable selected payloads may escape this callback. */
    public fun <T : Any> edgeCells(
        id: Int,
        key: IndexedPropertyKey<T>,
        layer: StorageLayer = StorageLayer.VISIBLE,
    ): CellReader<T> = access { registered().edgeCells(id, key, layer, lifetime) }

    private fun registered(): LayeredStorageImpl = checkNotNull(typed) { "Storage has no registered field capability" }

    /** Fixed visible node IDs; raw IDs must be reacquired after clear. */
    public fun getNodeIDs(): Set<Int> = access { storage.nodeIDs.toSet() }

    /** Fixed visible edge IDs. */
    public fun getEdgeIDs(): Set<Int> = access { storage.edgeIDs.toSet() }

    /** Whether [id] names a visible node. */
    public fun containsNode(id: Int): Boolean = access { storage.containsNode(id) }

    /** Whether [id] names a visible edge. */
    public fun containsEdge(id: Int): Boolean = access { storage.containsEdge(id) }

    /** Fixed structure of an existing edge; missing IDs retain the storage error. */
    public fun getEdgeStructure(id: Int): IStorage.EdgeStructure = access { storage.getEdgeStructure(id) }

    /** Fixed node property image, including independently copied mutable descendants. */
    public fun getNodeProperties(id: Int): Map<String, IValue> = access { capture(storage.getNodeProperties(id)) }

    /** Fixed visible edge property image. */
    public fun getEdgeProperties(id: Int): Map<String, IValue> = access { capture(storage.getEdgeProperties(id)) }

    /** Fixed layer image, null when the selected layer holds no row. */
    public fun getEdgeProperties(id: Int, layer: StorageLayer): Map<String, IValue>? = access {
        storage.getEdgeProperties(id, layer)?.let(::capture)
    }

    /** Independent selected node value, or null for absence. */
    public fun getNodeProperty(id: Int, name: String): IValue? = access { storage.getNodeProperty(id, name)?.deepCopy() }

    /** Independent selected edge value, or null for absence. */
    public fun getEdgeProperty(id: Int, name: String): IValue? = access { storage.getEdgeProperty(id, name)?.deepCopy() }

    /** Independent metadata value, or null for absence. */
    public fun getMeta(name: String): IValue? = access { storage.getMeta(name)?.deepCopy() }

    /** Selected incoming IDs, borrowed until callback exit; null [group] selects full adjacency. */
    public fun getIncomingEdges(id: Int, group: String? = null): Iterator<Int> = access {
        iterator(if (group == null) storage.getIncomingEdges(id) else storage.getIncomingEdges(id, group))
    }

    /** Selected outgoing IDs, borrowed until callback exit; null [group] selects full adjacency. */
    public fun getOutgoingEdges(id: Int, group: String? = null): Iterator<Int> = access {
        iterator(if (group == null) storage.getOutgoingEdges(id) else storage.getOutgoingEdges(id, group))
    }

    /** Fixed pair/triple selection in the existing encounter order. */
    public fun getEdges(src: Int, dst: Int, tag: String? = null): Set<Int> = access { storage.getEdges(src, dst, tag) }

    /** First visible pair/triple identity, or null for a missing match or endpoint. */
    public fun findEdge(src: Int, dst: Int, tag: String? = null): Int? = access {
        if (tag == null) storage.findEdge(src, dst) else storage.findEdge(src, dst, tag)
    }

    private fun <R> access(action: () -> R): R {
        lifetime.checkActive()
        return action()
    }

    private fun iterator(ids: Set<Int>): Iterator<Int> {
        val iterator = ids.iterator()
        return object : Iterator<Int> {
            override fun hasNext(): Boolean = access { iterator.hasNext() }
            override fun next(): Int = access {
                if (!iterator.hasNext()) throw NoSuchElementException()
                iterator.next()
            }
        }
    }
}

// Snapshot capture reuses Value ownership semantics and preserves the backend's encounter order.
internal fun capture(properties: Map<String, IValue>): Map<String, IValue> =
    properties.mapValues { (_, value) -> value.deepCopy() }
