package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal

/**
 * User-provided node identifier, stored as the `__id__` meta property.
 */
typealias NodeID = String

/**
 * Abstract base class for graph nodes with storage-backed property management.
 *
 * The node's [id] is the user-provided [NodeID], read from the `__id__` meta property.
 * The [internalId] is the storage-generated opaque key, invisible to external code.
 * Properties prefixed with `__` are internal metadata and filtered from external access.
 *
 * @property storage The storage system for node properties.
 * @property internalId The storage-generated opaque key for this node.
 * @see AbcEntity
 * @see IEntity
 */
abstract class AbcNode(
    protected val storage: IStorage,
    internal val internalId: InternalID,
) : AbcEntity() {
    /**
     * Represents the type information for a node.
     */
    interface Type : IEntity.Type

    /**
     * The user-provided node ID, read from `__id__` meta property.
     */
    override val id: NodeID
        get() = (storage.getNodeProperty(internalId, META_ID) as StrVal).core

    /**
     * Returns the node type information.
     *
     * @return The node's type.
     */
    abstract override val type: Type

    /**
     * Returns true if the target storage matches this node's storage.
     *
     * @param target The storage to compare.
     * @return True if storage matches, false otherwise.
     */
    fun doUseStorage(target: IStorage): Boolean = target == storage

    override fun get(name: String): IValue? {
        require(!name.startsWith(META_PREFIX)) { "Cannot access meta property: $name" }
        return storage.getNodeProperty(internalId, name)
    }

    override fun set(
        name: String,
        value: IValue?,
    ) {
        require(!name.startsWith(META_PREFIX)) { "Cannot set meta property: $name" }
        storage.setNodeProperties(internalId, mapOf(name to value))
    }

    override fun contains(name: String): Boolean {
        require(!name.startsWith(META_PREFIX)) { "Cannot query meta property: $name" }
        return storage.getNodeProperty(internalId, name) != null
    }

    override fun asMap(): Map<String, IValue> =
        storage.getNodeProperties(internalId).filterKeys { !it.startsWith(META_PREFIX) }

    override fun update(props: Map<String, IValue?>) {
        require(props.keys.none { it.startsWith(META_PREFIX) }) { "Cannot set meta properties" }
        storage.setNodeProperties(internalId, props)
    }

    override fun toString(): String = "{id=$id, type=${this.type}}"

    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = if (other is AbcNode) this.id == other.id else super.equals(other)

    companion object {
        internal const val META_PREFIX = "__"
        internal const val META_ID = "__id__"
    }
}
