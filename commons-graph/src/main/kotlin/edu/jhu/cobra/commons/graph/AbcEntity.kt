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
public sealed class AbcEntity : IEntity {
    /**
     * Returns the property value with the specified name, cast to type [T].
     *
     * @param name The property name.
     * @return The property value as [T], or null if absent or type does not match.
     * @see IEntity.get
     */
    public inline fun <reified T : IValue> getTypeProp(name: String): T? = get(name) as? T

    /**
     * Creates a delegate for a non-nullable typed property.
     *
     * @param optName Optional custom property name; uses the property name if null.
     * @param default The default value if the property is absent.
     * @return A [ReadWriteProperty] delegate for property access and modification.
     */
    @Suppress("FunctionName")
    protected inline fun <reified T : IValue> EntityProperty(
        optName: String? = null,
        default: T,
    ): ReadWriteProperty<IEntity, T> =
        object : ReadWriteProperty<IEntity, T> {
            override fun getValue(
                thisRef: IEntity,
                property: KProperty<*>,
            ): T = thisRef[optName ?: property.name] as? T ?: default

            override fun setValue(
                thisRef: IEntity,
                property: KProperty<*>,
                value: T,
            ) {
                thisRef[optName ?: property.name] = value
            }
        }

    /**
     * Creates a delegate for a nullable typed property.
     *
     * @param optName Optional custom property name; uses the property name if null.
     * @return A [ReadWriteProperty] delegate for property access and modification.
     */
    @Suppress("FunctionName")
    protected inline fun <reified T : IValue?> EntityProperty(optName: String? = null): ReadWriteProperty<IEntity, T?> =
        object : ReadWriteProperty<IEntity, T?> {
            override fun getValue(
                thisRef: IEntity,
                property: KProperty<*>,
            ): T? = thisRef[optName ?: property.name] as? T

            override fun setValue(
                thisRef: IEntity,
                property: KProperty<*>,
                value: T?,
            ) {
                thisRef[optName ?: property.name] = value
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
        optName: String? = null,
        default: T,
    ): ReadWriteProperty<IEntity, T> =
        object : ReadWriteProperty<IEntity, T> {
            // The delegate is created per entity wrapper, so it holds references only: whatever
            // depends on a class alone is built once in [EntityTypeTables]. The enclosing entity's
            // class names the prefix; the delegate itself is an anonymous class.
            private val propPrefix = EntityTypeTables.prefixOf(this@AbcEntity.javaClass)

            private fun propName(property: KProperty<*>): String = optName ?: "${propPrefix}_${property.name}"

            override fun getValue(
                thisRef: IEntity,
                property: KProperty<*>,
            ): T {
                val propName = propName(property)
                val typeStrVal = thisRef[propName] as? StrVal
                return typeStrVal?.core?.let { EntityTypeTables.constantsByName(T::class.java)[it] } ?: default
            }

            override fun setValue(
                thisRef: IEntity,
                property: KProperty<*>,
                value: T,
            ) {
                val propName = propName(property)
                val prevValue = thisRef[propName] as? StrVal
                if (prevValue?.core == value.name) return
                thisRef[propName] = value.name.strVal
            }
        }
}

/**
 * Class-keyed tables behind the `EntityType` delegate, each built once per class and shared by every
 * delegate and entity wrapper.
 *
 * [ClassValue] computes a value at most once per class and is safe to read from several threads.
 */
@PublishedApi
internal object EntityTypeTables {
    private val constantsTable =
        object : ClassValue<Map<String, IEntity.Type>>() {
            override fun computeValue(type: Class<*>): Map<String, IEntity.Type> =
                type.enumConstants.associate { (it as IEntity.Type).name to it }
        }

    private val prefixTable =
        object : ClassValue<String>() {
            override fun computeValue(type: Class<*>): String = type.simpleName.lowercase()
        }

    /**
     * Returns the constants of the enum class [type] keyed by name.
     *
     * @param type The enum class implementing [IEntity.Type].
     * @return The one map of [type], identical for every call.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : IEntity.Type> constantsByName(type: Class<T>): Map<String, T> = constantsTable.get(type) as Map<String, T>

    /**
     * Returns the lowercase simple name of the entity class [entityClass], the prefix of an automatic property name.
     *
     * @param entityClass The concrete entity class.
     * @return The prefix of [entityClass].
     */
    fun prefixOf(entityClass: Class<*>): String = prefixTable.get(entityClass)
}
