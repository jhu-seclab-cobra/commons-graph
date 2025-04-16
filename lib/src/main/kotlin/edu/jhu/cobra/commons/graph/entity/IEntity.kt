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
         * The value of the identifier. This is a flexible property that can hold any type of value
         * defining the identity of an entity, allowing for complex identification mechanisms.
         */
        val serialize: IValue
    }

    /**
     * The unique identifier of the entity, encapsulated in an ID interface. This identifier is used
     * to distinguish each entity uniquely within the graph.
     */
    interface Type {
        val name: String
    }

    val id: ID

    /**
     * Optional property to specify the type of the entity. This can be used to apply different processing
     * rules or data handling procedures depending on the type of the entity.
     */
    val type: Type

    fun setProp(name: String, value: IValue?)

    fun setProps(props: Map<String, IValue?>)

    fun getProp(name: String): IValue?

    fun getAllProps(): Map<String, IValue>
}
