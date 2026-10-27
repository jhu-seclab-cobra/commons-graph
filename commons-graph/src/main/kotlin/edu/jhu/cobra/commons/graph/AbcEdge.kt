package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.poset.PosetTrait
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.listVal

/**
 * The identity of an edge: its source node ID, destination node ID and tag.
 *
 * @property src The source node ID.
 * @property dst The destination node ID.
 * @property tag The edge tag.
 */
public data class EdgeKey(
    val src: NodeID,
    val dst: NodeID,
    val tag: String,
)

/**
 * Abstract base class for graph edges with storage-backed property management.
 *
 * Structural info (source, destination, tag) is injected at bind time by the
 * graph layer — no lazy storage lookup needed. The `(src, dst, tag)` triple
 * serves as the identity for [equals] and [hashCode]; the derived [id]
 * (source-tag-destination) is a display form only.
 *
 * Subclasses use a no-arg constructor. The graph layer calls [bind] after
 * creation to inject storage and edge identity — these are not constructor
 * parameters, keeping subclass constructors free of infrastructure concerns.
 *
 * @see AbcEntity
 * @see IEntity
 */
public abstract class AbcEdge : AbcEntity() {
    /**
     * Represents the type information for an edge.
     */
    public interface Type : IEntity.Type

    /** Backing storage, injected by the graph layer via [bind]. */
    protected lateinit var storage: IStorage
        private set

    /** The storage-internal Int ID, injected by the graph layer via [bind]. */
    public var storageId: Int = -1
        internal set

    /** Source node ID, injected at bind time. */
    public lateinit var srcNid: NodeID
        internal set

    /** Destination node ID, injected at bind time. */
    public lateinit var dstNid: NodeID
        internal set

    /** Edge tag name, injected at bind time. */
    public lateinit var eTag: String
        internal set

    /**
     * Initializes this edge with the given storage and structural info.
     * Called by the graph layer after construction — must not be called by user code.
     */
    internal fun bind(
        storage: IStorage,
        storageId: Int,
        srcNid: NodeID,
        dstNid: NodeID,
        tag: String,
    ) {
        this.storage = storage
        this.storageId = storageId
        this.srcNid = srcNid
        this.dstNid = dstNid
        this.eTag = tag
    }

    /** The identity of this edge: the `(src, dst, tag)` triple. */
    public val key: EdgeKey get() = EdgeKey(srcNid, dstNid, eTag)

    /**
     * The edge identifier derived from source, tag, and destination.
     * Display form only — the joined string is ambiguous when a component
     * contains the delimiter; identity is the `(src, dst, tag)` triple.
     */
    override val id: String get() = "$srcNid-$eTag-$dstNid"

    /**
     * Visibility labels assigned to this edge.
     *
     * The setter is internal: assignments go through
     * [edu.jhu.cobra.commons.graph.poset.PosetTrait], which keeps the owning
     * graph's label index complete.
     *
     * @return Set of labels currently assigned.
     */
    public var labels: Set<Label>
        get() {
            val raw = storage.getEdgeProperty(storageId, LABELS_PROP) as? ListVal ?: return emptySet()
            return raw.core.mapTo(HashSet(raw.core.size)) { Label((it as StrVal).core) }
        }
        internal set(values) {
            storage.setEdgeProperties(storageId, mapOf(LABELS_PROP to values.map { it.core }.listVal))
        }

    /** Whether the bound storage still contains this edge's [storageId]. */
    internal fun isStored(): Boolean = storage.containsEdge(storageId)

    override fun get(name: String): IValue? = storage.getEdgeProperty(storageId, name)

    override fun set(
        name: String,
        value: IValue?,
    ) {
        storage.setEdgeProperties(storageId, mapOf(name to value))
    }

    override fun contains(name: String): Boolean = storage.getEdgeProperty(storageId, name) != null

    // Columnar storages return live views; copying here upholds the IEntity
    // snapshot contract regardless of the backing storage.
    override fun asMap(): Map<String, IValue> = storage.getEdgeProperties(storageId).toMap()

    override fun update(props: Map<String, IValue?>) {
        storage.setEdgeProperties(storageId, props)
    }

    override fun toString(): String = "{$srcNid-$eTag-$dstNid, ${this.type}}"

    override fun hashCode(): Int = Triple(srcNid, dstNid, eTag).hashCode()

    override fun equals(other: Any?): Boolean = other is AbcEdge && srcNid == other.srcNid && dstNid == other.dstNid && eTag == other.eTag

    internal companion object {
        // Storage property holding an edge's visibility labels; fixed by the entity design (design-entity.md).
        const val LABELS_PROP = "labels"

        /** The law of [LABELS_PROP]: [PosetTrait.mergeLabels] over the stored label lists. */
        val LABELS_LAW: PropertyMergeLaw = PropertyMergeLaw(::mergeStoredLabels)

        /**
         * Merges stored label lists. Membership follows [PosetTrait.mergeLabels]; key presence is resolved
         * apart from it: a writer creating the key against a writer deleting it fails, a created key stays
         * present even when empty, and a deleted key is absent unless the merged labels are not empty.
         */
        private fun mergeStoredLabels(
            base: IValue?,
            changes: Collection<PropertyChange>,
        ): IValue? {
            val creates = changes.any { it.before == null && it.after != null }
            val deletes = changes.any { it.before != null && it.after == null }
            if (creates && deletes) throw MergeConflictException("labels key is created and deleted")
            val baseLabels = labelsOf(base)
            val merged =
                PosetTrait.mergeLabels(
                    baseLabels,
                    changes.map { WriterChange(labelsOf(it.before), labelsOf(it.after)) },
                )
            return when {
                deletes && merged.isEmpty() -> null
                merged == baseLabels && (base != null || !creates) -> base
                else -> merged.map { it.core }.sorted().listVal
            }
        }

        private fun labelsOf(value: IValue?): Set<Label> =
            when (value) {
                null -> emptySet()
                is ListVal -> value.core.mapTo(HashSet()) { Label((it as StrVal).core) }
                else -> throw IllegalArgumentException("labels property is not a list: $value")
            }
    }
}
