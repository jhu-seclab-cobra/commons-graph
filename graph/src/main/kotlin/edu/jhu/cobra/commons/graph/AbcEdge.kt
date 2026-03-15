package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcNode.Companion.META_PREFIX
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.listVal

/**
 * Abstract base class for graph edges with storage-backed property management.
 *
 * The edge's [id] is the graph-layer identity string in `srcNid-eType-dstNid` format.
 * The [internalId] is the storage-generated opaque key, invisible to external code.
 * Structural info (source, destination, type) is resolved via [IStorage.getEdgeSrc],
 * [IStorage.getEdgeDst], [IStorage.getEdgeType] combined with [nodeIdResolver] for
 * InternalID → NodeID translation.
 * Properties prefixed with `__` are internal metadata and filtered from external access.
 *
 * @property storage The storage system for edge properties.
 * @property internalId The storage-generated opaque key for this edge.
 * @property nodeIdResolver Resolves storage InternalID to user-provided NodeID.
 * @see AbcEntity
 * @see IEntity
 */
abstract class AbcEdge(
    protected val storage: IStorage,
    internal val internalId: InternalID,
    private val nodeIdResolver: (InternalID) -> NodeID,
) : AbcEntity() {
    /**
     * Represents the type information for an edge.
     */
    interface Type : IEntity.Type

    /** Source node ID, resolved from storage edge structure. */
    val srcNid: NodeID by lazy(LazyThreadSafetyMode.NONE) {
        nodeIdResolver(storage.getEdgeSrc(internalId))
    }

    /** Destination node ID, resolved from storage edge structure. */
    val dstNid: NodeID by lazy(LazyThreadSafetyMode.NONE) {
        nodeIdResolver(storage.getEdgeDst(internalId))
    }

    /** Edge type name, resolved from storage edge structure. */
    val eType: String by lazy(LazyThreadSafetyMode.NONE) {
        storage.getEdgeType(internalId)
    }

    /**
     * The graph-layer edge identity in `srcNid-eType-dstNid` format.
     */
    override val id: String get() = "$srcNid-$eType-$dstNid"

    /**
     * Visibility labels assigned to this edge.
     *
     * @return Set of labels currently assigned.
     */
    var labels: Set<Label>
        get() {
            val raw = storage.getEdgeProperty(internalId, "labels") as? ListVal ?: return emptySet()
            return raw.core.mapTo(HashSet(raw.core.size)) { Label((it as StrVal).core) }
        }
        set(values) {
            storage.setEdgeProperties(internalId, mapOf("labels" to values.map { it.core }.listVal))
        }

    override fun get(name: String): IValue? {
        require(!name.startsWith(META_PREFIX)) { "Cannot access meta property: $name" }
        return storage.getEdgeProperty(internalId, name)
    }

    override fun set(
        name: String,
        value: IValue?,
    ) {
        require(!name.startsWith(META_PREFIX)) { "Cannot set meta property: $name" }
        storage.setEdgeProperties(internalId, mapOf(name to value))
    }

    override fun contains(name: String): Boolean {
        require(!name.startsWith(META_PREFIX)) { "Cannot query meta property: $name" }
        return storage.getEdgeProperty(internalId, name) != null
    }

    override fun asMap(): Map<String, IValue> = storage.getEdgeProperties(internalId).filterKeys { !it.startsWith(META_PREFIX) }

    override fun update(props: Map<String, IValue?>) {
        require(props.keys.none { it.startsWith(META_PREFIX) }) { "Cannot set meta properties" }
        storage.setEdgeProperties(internalId, props)
    }

    override fun toString(): String = "{$srcNid-$eType-$dstNid, ${this.type}}"

    override fun hashCode(): Int = internalId.hashCode()

    override fun equals(other: Any?): Boolean = if (other is AbcEdge) this.internalId == other.internalId else super.equals(other)

    companion object {
        internal const val META_SRC = "__src__"
        internal const val META_DST = "__dst__"
        internal const val META_TAG = "__tag__"
    }
}
