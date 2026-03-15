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
 * Structural info (source, destination, type) is read from meta properties
 * (`__src__`, `__dst__`, `__tag__`), which store user-provided [NodeID]s.
 * Properties prefixed with `__` are internal metadata and filtered from external access.
 *
 * @property storage The storage system for edge properties.
 * @property internalId The storage-generated opaque key for this edge.
 * @see AbcEntity
 * @see IEntity
 */
abstract class AbcEdge(
    protected val storage: IStorage,
    internal val internalId: InternalID,
) : AbcEntity() {
    /**
     * Represents the type information for an edge.
     */
    interface Type : IEntity.Type

    /** Source node ID, read from `__src__` meta property. */
    val srcNid: NodeID by lazy(LazyThreadSafetyMode.NONE) {
        (storage.getEdgeProperty(internalId, META_SRC) as StrVal).core
    }

    /** Destination node ID, read from `__dst__` meta property. */
    val dstNid: NodeID by lazy(LazyThreadSafetyMode.NONE) {
        (storage.getEdgeProperty(internalId, META_DST) as StrVal).core
    }

    /** Edge type name, read from `__tag__` meta property. */
    val eType: String by lazy(LazyThreadSafetyMode.NONE) {
        (storage.getEdgeProperty(internalId, META_TAG) as StrVal).core
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
            return raw.core.map { Label(it.core.toString()) }.toSet()
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
