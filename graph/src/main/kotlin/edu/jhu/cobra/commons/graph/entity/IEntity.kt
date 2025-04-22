package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.value.IValue

/**
 * Represents an abstract entity within a graph. This sealed interface serves as a base for both nodes and edges
 * within the graph structure, providing a unified approach to handling graph components.
 *
 * Entities in a graph are identified uniquely and can be of various types, enabling polymorphic behavior
 * and type-specific processing in graph operations.
 */
sealed interface IEntity {

    /**
     * Represents a unique identifier for an entity in the graph. This interface ensures that all entities,
     * whether they are nodes or edges, have an identifier that can encapsulate any form of identity representation,
     * such as numbers, strings, or custom objects.
     */
    sealed interface ID {
        /**
         * The serialized value of the identifier. This property holds the actual value that defines
         * the identity of an entity, allowing for complex identification mechanisms.
         *
         * @return The serialized value representing the entity's identifier.
         */
        val serialize: IValue
    }

    /**
     * Represents the type information of an entity. This interface provides a way to categorize
     * and distinguish different kinds of entities within the graph.
     */
    interface Type {
        /**
         * The name of the entity type.
         *
         * @return The string representation of the entity type.
         */
        val name: String
    }

    /**
     * The unique identifier of the entity. This identifier is used to distinguish each entity
     * uniquely within the graph.
     *
     * @return The identifier of the entity.
     */
    val id: ID

    /**
     * The type information of the entity. This property can be used to apply different processing
     * rules or data handling procedures depending on the type of the entity.
     *
     * @return The type information of the entity.
     */
    val type: Type

    /**
     * Sets a property value for the entity.
     *
     * @param name The name of the property to set.
     * @param value The value to associate with the property, or `null` to remove the property.
     */
    fun setProp(name: String, value: IValue?)

    /**
     * Sets multiple properties for the entity at once.
     *
     * @param props A map of property names to their corresponding values.
     *              A `null` value indicates that the property should be removed.
     */
    fun setProps(props: Map<String, IValue?>)

    /**
     * Retrieves the value of a property from the entity.
     *
     * @param name The name of the property to retrieve.
     * @return The value of the property if it exists, `null` otherwise.
     */
    fun getProp(name: String): IValue?

    /**
     * Retrieves all properties of the entity.
     *
     * @return A map containing all properties of the entity, where the key is the property name
     *         and the value is the property value.
     */
    fun getAllProps(): Map<String, IValue>
}
