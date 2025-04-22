package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.toTypeArray
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal

/**
 * Represents a unique identifier for a node in the graph. This data class encapsulates the name
 * information necessary to uniquely identify a node.
 *
 * @property name The string representation of the node identifier.
 */
data class NodeID(val name: String) : IEntity.ID {
    /**
     * The serialized representation of the node identifier as a string value.
     */
    override val serialize: StrVal get() = name.strVal

    override fun toString() = name

    /**
     * Creates a node identifier from a string value.
     *
     * @param strVal The string value representing the node identifier.
     */
    constructor(strVal: StrVal) : this(strVal.core)
}

/**
 * Abstract base class for nodes in the graph. This class implements the [IEntity] interface and provides
 * common functionality for node operations, including property management and identity comparison.
 *
 * @property storage The storage system used to persist node properties and relationships.
 */
abstract class AbcNode(private val storage: IStorage) : IEntity {

    /**
     * Interface representing the type information for a node.
     */
    interface Type : IEntity.Type

    /**
     * The unique identifier of the node.
     */
    abstract override val id: NodeID

    /**
     * The type information of the node.
     */
    abstract override val type: Type

    /**
     * Checks if the provided target storage is the same as the storage associated with this node.
     *
     * @param target The target storage to compare.
     * @return `true` if the target storage matches the node's storage, `false` otherwise.
     */
    fun doUseStorage(target: IStorage) = target == storage

    /**
     * Sets a property value for the node.
     *
     * @param name The name of the property to set.
     * @param value The value to associate with the property.
     */
    override fun setProp(name: String, value: IValue?) =
        storage.setNodeProperties(id, name to value)

    /**
     * Sets multiple properties for the node at once.
     *
     * @param props A map of property names to their corresponding values.
     */
    override fun setProps(props: Map<String, IValue?>) =
        storage.setNodeProperties(id, *props.toTypeArray())

    /**
     * Retrieves the value of a property from the node.
     *
     * @param name The name of the property to retrieve.
     * @return The value of the property if it exists, `null` otherwise.
     */
    override fun getProp(name: String): IValue? = storage.getNodeProperty(id, name)

    /**
     * Retrieves all properties of the node.
     *
     * @return A map containing all properties of the node, where the key is the property name
     *         and the value is the property value.
     */
    override fun getAllProps() = storage.getNodeProperties(id)

    /**
     * Returns a string representation of the node.
     *
     * @return A string containing the node identifier and type information.
     */
    override fun toString(): String = "{id=${id}, type=${this.type}}"

    /**
     * Returns a hash code value for the node.
     *
     * @return The hash code value based on the string representation of the node.
     */
    override fun hashCode(): Int = toString().hashCode()

    /**
     * Compares this node with another object for equality.
     *
     * @param other The object to compare with.
     * @return `true` if the other object is a node with the same identifier, `false` otherwise.
     */
    override fun equals(other: Any?): Boolean = if (other is AbcNode) this.id == other.id else super.equals(other)
}
