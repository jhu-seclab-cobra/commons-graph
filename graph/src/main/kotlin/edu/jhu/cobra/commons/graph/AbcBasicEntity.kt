package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Base entity for graph nodes and edges with property management.
 *
 * Serves as a superclass for [AbcNode] and [AbcEdge]. Provides property delegate utilities for typed property access.
 *
 * @see IEntity
 * @see AbcNode
 * @see AbcEdge
 */
sealed class AbcBasicEntity : IEntity {

    /**
     * Returns the property value with the specified name, cast to type [T].
     *
     * @param name The property name.
     * @return The property value as [T], or null if absent or type does not match.
     * @see IEntity.getProp
     */
    inline fun <reified T : IValue> getTypeProp(name: String): T? = getProp(name) as? T

    /**
     * Creates a delegate for a non-nullable typed property.
     *
     * @param optName Optional custom property name; uses the property name if null.
     * @param default The default value if the property is absent.
     * @return A [ReadWriteProperty] delegate for property access and modification.
     * @see IEntity.setProp
     */
    @Suppress("FunctionName")
    protected inline fun <reified T : IValue> EntityProperty(
        optName: String? = null, default: T
    ) = object : ReadWriteProperty<IEntity, T> {
        override fun getValue(thisRef: IEntity, property: KProperty<*>): T =
            thisRef.getProp(optName ?: property.name) as? T ?: default

        override fun setValue(thisRef: IEntity, property: KProperty<*>, value: T) =
            thisRef.setProp(optName ?: property.name, value)
    }

    /**
     * Creates a delegate for a nullable typed property.
     *
     * @param optName Optional custom property name; uses the property name if null.
     * @return A [ReadWriteProperty] delegate for property access and modification.
     * @see IEntity.setProp
     */
    @Suppress("FunctionName")
    protected inline fun <reified T : IValue?> EntityProperty(
        optName: String? = null
    ) = object : ReadWriteProperty<IEntity, T?> {
        override fun getValue(thisRef: IEntity, property: KProperty<*>): T? =
            thisRef.getProp(optName ?: property.name) as? T

        override fun setValue(thisRef: IEntity, property: KProperty<*>, value: T?) {
            value?.let { thisRef.setProp(optName ?: property.name, it) }
        }
    }

    /**
     * Creates a delegate for an entity type property using an enum type.
     *
     * Property names are automatically prefixed with the lowercase class name if [optName] is not provided.
     *
     * @param optName Optional custom property name; auto-generated if null.
     * @param default The default enum value if the property is absent.
     * @return A [ReadWriteProperty] delegate for type property access and modification.
     * @see IEntity.Type
     */
    @Suppress("FunctionName")
    protected inline fun <reified T : IEntity.Type> EntityType(
        optName: String? = null, default: T
    ) = object : ReadWriteProperty<IEntity, T> {
        private val propPrefix by lazy { this::class.java.simpleName.lowercase() }
        private val enumTypeMap by lazy { T::class.java.enumConstants.associateBy { it.name } }

        override fun getValue(thisRef: IEntity, property: KProperty<*>): T {
            val propName = optName ?: "${propPrefix}_${property.name}"
            val typeStrVal = thisRef.getProp(propName) as? StrVal
            return typeStrVal?.core?.let { enumTypeMap[it] } ?: default
        }

        override fun setValue(thisRef: IEntity, property: KProperty<*>, value: T) {
            val propName = optName ?: "${propPrefix}_${property.name}"
            val prevValue = thisRef.getProp(propName) as? StrVal
            if (prevValue?.core == value.name) return
            thisRef.setProp(name = propName, value = value.name.strVal)
        }
    }
}