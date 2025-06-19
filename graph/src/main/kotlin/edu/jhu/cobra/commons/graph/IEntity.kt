package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.IPrimitiveVal
import edu.jhu.cobra.commons.value.IValue

/**
 * Serves as the base interface for all graph entities, including nodes and edges.
 *
 * Entities are uniquely identified and can store typed properties for flexible graph modeling.
 */
sealed interface IEntity {

    /**
     * Uniquely identifies an entity within the graph.
     *
     * Supports various identifier types (e.g., string, number).
     */
    sealed interface ID {
        /**
         * Returns the serialized value representing this identifier.
         *
         * @return The serialized identifier value.
         */
        val serialize: IValue

        /**
         * Returns the human-readable name or label for this identifier.
         *
         * @return The identifier name.
         */
        val name: String
    }

    /**
     * Categorizes the type of entity (e.g., node, edge).
     */
    interface Type {
        /**
         * Returns the type name of the entity.
         *
         * @return The entity type name.
         */
        val name: String
    }

    /**
     * Returns the unique identifier for this entity.
     *
     * @return The entity's identifier.
     */
    val id: ID

    /**
     * Returns the type information for this entity.
     *
     * @return The entity's type.
     */
    val type: Type

    /**
     * Sets a primitive property value by name (cache only).
     *
     * @param byName The property name.
     * @param newVal The new primitive value, or null to remove.
     */
    operator fun set(byName: String, newVal: IPrimitiveVal?) = setProp(byName, newVal)

    /**
     * Sets a property value by name.
     *
     * @param name The property name.
     * @param value The value to set, or null to remove.
     */
    fun setProp(name: String, value: IValue?)

    /**
     * Sets multiple properties at once.
     *
     * @param props Map of property names to values (null to remove).
     */
    fun setProps(props: Map<String, IValue?>)

    /**
     * Returns a primitive property value by name (cache only).
     *
     * @param byName The property name.
     * @return The primitive value, or null if absent.
     */
    operator fun get(byName: String): IPrimitiveVal? = getProp(byName) as? IPrimitiveVal

    /**
     * Returns a property value by name.
     *
     * @param name The property name.
     * @return The property value, or null if absent.
     */
    fun getProp(name: String): IValue?

    /**
     * Returns all properties of this entity.
     *
     * @return Map of property names to values.
     */
    fun getAllProps(): Map<String, IValue>

    /**
     * Returns true if the property exists in the cache.
     *
     * @param name The property name.
     * @return True if present in cache, false otherwise.
     */
    fun containProp(name: String): Boolean

    /**
     * Returns true if the property exists in the cache.
     *
     * @param byName The property name.
     * @return True if present in cache, false otherwise.
     */
    operator fun contains(byName: String): Boolean = containProp(byName)

}
