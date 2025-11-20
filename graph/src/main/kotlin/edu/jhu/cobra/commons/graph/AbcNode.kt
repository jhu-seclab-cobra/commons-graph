package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal

/**
 * Unique identifier for a node in the graph.
 *
 * Represents an immutable identifier as a string value.
 *
 * @property name The node identifier string.
 * @constructor Creates a [NodeID] from a string.
 * @param name The node identifier string.
 * @see IEntity.ID
 */
data class NodeID(override val name: String) : IEntity.ID {
    /**
     * Returns the serialized node identifier as a [StrVal].
     *
     * @return The string value representation.
     */
    override val serialize: StrVal get() = name.strVal
    override fun toString() = name

    /**
     * Creates a [NodeID] from a [StrVal].
     *
     * @param strVal The string value representing the node identifier.
     */
    constructor(strVal: StrVal) : this(strVal.core)
}

/**
 * Abstract base class for graph nodes with storage-backed property management.
 *
 * Provides property access, identity management, and storage integration for nodes.
 *
 * @property storage The storage system for node properties.
 * @constructor Creates a node with the given [IStorage].
 * @param storage The storage system for node properties.
 * @see AbcBasicEntity
 * @see IEntity
 * @see NodeID
 */
abstract class AbcNode(protected val storage: IStorage) : AbcBasicEntity() {
    /**
     * Represents the type information for a node.
     */
    interface Type : IEntity.Type

    /**
     * Returns the unique node identifier.
     *
     * @return The node's identifier.
     */
    abstract override val id: NodeID

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

    /**
     * Sets a property value for the node.
     *
     * @param name The property name.
     * @param value The property value.
     */
    override fun setProp(name: String, value: IValue?) =
        storage.setNodeProperties(id, mapOf(name to value))

    /**
     * Sets multiple properties for the node.
     *
     * @param props Map of property names to values.
     */
    override fun setProps(props: Map<String, IValue?>) =
        storage.setNodeProperties(id, props)

    /**
     * Returns a property value from the node.
     *
     * @param name The property name.
     * @return The property value, or null if absent.
     */
    override fun getProp(name: String): IValue? = storage.getNodeProperties(id)[name]

    /**
     * Returns all properties of the node.
     *
     * @return Map of property names to values.
     */
    override fun getAllProps(): Map<String, IValue> = storage.getNodeProperties(id)

    /**
     * Returns true if the node contains the specified property.
     *
     * @param name The property name.
     * @return True if the property exists, false otherwise.
     */
    override fun containProp(name: String): Boolean = name in storage.getNodeProperties(id)

    /**
     * Returns a string representation of the node.
     *
     * @return String containing node ID and type.
     */
    override fun toString(): String = "{id=${id}, type=${this.type}}"

    /**
     * Returns the hash code based on string representation.
     *
     * @return The hash code value.
     */
    override fun hashCode(): Int = toString().hashCode()

    /**
     * Compares this node with another object for equality.
     *
     * @param other The object to compare with.
     * @return True if other is a node with the same ID, false otherwise.
     */
    override fun equals(other: Any?): Boolean = if (other is AbcNode) this.id == other.id else super.equals(other)
}