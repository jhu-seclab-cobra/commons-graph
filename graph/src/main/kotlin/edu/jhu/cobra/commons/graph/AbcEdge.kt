package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcNode.Companion.META_PREFIX
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.listVal

/**
 * Abstract base class for graph edges with storage-backed property management.
 *
 * The edge's [id] is the storage edge identifier. Structural info (source, destination, type)
 * is resolved via [IStorage.getEdgeSrc], [IStorage.getEdgeDst], [IStorage.getEdgeType].
 * Properties prefixed with `__` are internal metadata and filtered from external access.
 *
 * @property storage The storage system for edge properties.
 * @property edgeId The storage edge identifier.
 * @see AbcEntity
 * @see IEntity
 */
abstract class AbcEdge(
    protected val storage: IStorage,
    val edgeId: String,
) : AbcEntity() {
    /**
     * Represents the type information for an edge.
     */
    interface Type : IEntity.Type

    /** Source node ID, resolved from storage edge structure. */
    val srcNid: NodeID by lazy(LazyThreadSafetyMode.NONE) {
        storage.getEdgeSrc(edgeId)
    }

    /** Destination node ID, resolved from storage edge structure. */
    val dstNid: NodeID by lazy(LazyThreadSafetyMode.NONE) {
        storage.getEdgeDst(edgeId)
    }

    /** Edge type name, resolved from storage edge structure. */
    val eType: String by lazy(LazyThreadSafetyMode.NONE) {
        storage.getEdgeType(edgeId)
    }

    /**
     * The edge's storage identifier.
     */
    override val id: String get() = edgeId

    /**
     * Visibility labels assigned to this edge.
     *
     * @return Set of labels currently assigned.
     */
    var labels: Set<Label>
        get() {
            val raw = storage.getEdgeProperty(edgeId, "labels") as? ListVal ?: return emptySet()
            return raw.core.mapTo(HashSet(raw.core.size)) { Label((it as StrVal).core) }
        }
        set(values) {
            storage.setEdgeProperties(edgeId, mapOf("labels" to values.map { it.core }.listVal))
        }

    override fun get(name: String): IValue? {
        require(!name.startsWith(META_PREFIX)) { "Cannot access meta property: $name" }
        return storage.getEdgeProperty(edgeId, name)
    }

    override fun set(
        name: String,
        value: IValue?,
    ) {
        require(!name.startsWith(META_PREFIX)) { "Cannot set meta property: $name" }
        storage.setEdgeProperties(edgeId, mapOf(name to value))
    }

    override fun contains(name: String): Boolean {
        require(!name.startsWith(META_PREFIX)) { "Cannot query meta property: $name" }
        return storage.getEdgeProperty(edgeId, name) != null
    }

    override fun asMap(): Map<String, IValue> {
        return storage.getEdgeProperties(edgeId).filterKeys { !it.startsWith(META_PREFIX) }
    }

    override fun update(props: Map<String, IValue?>) {
        require(props.keys.none { it.startsWith(META_PREFIX) }) { "Cannot set meta properties" }
        storage.setEdgeProperties(edgeId, props)
    }

    override fun toString(): String = "{$srcNid-$eType-$dstNid, ${this.type}}"

    override fun hashCode(): Int = edgeId.hashCode()

    override fun equals(other: Any?): Boolean = if (other is AbcEdge) this.edgeId == other.edgeId else super.equals(other)

    companion object {
        internal const val META_SRC = "__src__"
        internal const val META_DST = "__dst__"
        internal const val META_TAG = "__tag__"
    }
}
