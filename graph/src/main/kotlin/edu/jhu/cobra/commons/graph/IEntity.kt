package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.IValue

/**
 * Storage-generated opaque identifier for internal entities (nodes, edges).
 * External code should not parse or interpret these IDs.
 */
typealias InternalID = Int

/**
 * Base interface for all graph entities, including nodes and edges.
 *
 * Provides unique identification and typed property storage for flexible graph modeling.
 * Properties are accessed via operator syntax: `entity["key"]`, `entity["key"] = value`,
 * `"key" in entity`.
 *
 * @see AbcNode
 * @see AbcEdge
 */
sealed interface IEntity {
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
     * For nodes, this is the user-facing name (uname).
     * For edges, this is the storage-generated edge ID.
     *
     * @return The entity's identifier string.
     */
    val id: String

    /**
     * Returns the type information for this entity.
     *
     * @return The entity's type.
     */
    val type: Type

    /**
     * Returns a property value by name.
     *
     * @param name The property name.
     * @return The property value, or null if absent.
     */
    operator fun get(name: String): IValue?

    /**
     * Sets a property value by name. Pass null to remove.
     *
     * @param name The property name.
     * @param value The value to set, or null to remove.
     */
    operator fun set(
        name: String,
        value: IValue?,
    )

    /**
     * Returns true if the property exists.
     *
     * @param name The property name.
     * @return True if present, false otherwise.
     */
    operator fun contains(name: String): Boolean

    /**
     * Returns all properties as an immutable map snapshot.
     *
     * @return Map of property names to values.
     */
    fun asMap(): Map<String, IValue>

    /**
     * Updates multiple properties at once. Null values remove properties.
     *
     * @param props Map of property names to values (null to remove).
     */
    fun update(props: Map<String, IValue?>)
}
