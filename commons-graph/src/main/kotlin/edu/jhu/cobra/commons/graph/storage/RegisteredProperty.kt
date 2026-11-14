package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

// A transfer image contains captured values, not a second authoritative row store.
internal typealias CapturedRow = Map<String, CapturedProperty>

internal sealed interface CapturedProperty {
    fun compatibility(name: String): IValue?
    fun copyInto(name: String, target: MutableMap<String, IValue>)
    fun isPresent(name: String): Boolean
}

internal class LegacyProperty(val value: IValue) : CapturedProperty, PropertyValue {
    override val key: PropertyKey<*>? get() = null
    override fun <T : Any> get(key: PropertyKey<T>): T = error("Unregistered legacy content for ${key.name}")
    override fun members(key: IndexedPropertyKey<*>): List<String> = error("Legacy content is not membership")
    override fun snapshot(): IValue = value.deepCopy()
    override fun equals(other: Any?): Boolean = other is LegacyProperty && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun compatibility(name: String): IValue = value
    override fun copyInto(name: String, target: MutableMap<String, IValue>) { target[name] = value }
    override fun isPresent(name: String): Boolean = true
}

internal class CapturedField<T : Any>(override val key: PropertyKey<T>, val value: T) : CapturedProperty, PropertyValue {
    override fun <V : Any> get(key: PropertyKey<V>): V = checkNotNull(key.value(this))
    override fun members(key: IndexedPropertyKey<*>): List<String> = error("Scalar content is not membership")
    override fun snapshot(): IValue = key.codec.encode(value)
    override fun equals(other: Any?): Boolean =
        other is CapturedField<*> && key.agrees(other.key) && value == other.value
    override fun hashCode(): Int = 31 * key.name.hashCode() + value.hashCode()
    override fun compatibility(name: String): IValue = key.codec.encode(value)
    override fun copyInto(name: String, target: MutableMap<String, IValue>) { target[name] = compatibility(name) }
    override fun isPresent(name: String): Boolean = true
}

@Suppress("UNCHECKED_CAST") // Key registration and runtime class establish the erased payload type once.
internal fun <T : Any> PropertyKey<T>.value(property: CapturedProperty?): T? {
    if (property == null) return null
    check(property is CapturedField<*> && agrees(property.key)) { "Wrong stored field for $name" }
    return property.value as T
}

// Store-owned dictionaries use opaque strings only; these ordinals are never persisted graph identities.
internal class PropertySymbols {
    private val ordinals = HashMap<String, Int>()
    private val values = ArrayList<String>()

    fun find(value: String): Int? = ordinals[value]

    fun intern(value: String): Int = ordinals[value] ?: run {
        check(values.size < Int.MAX_VALUE) { "Property dictionary capacity exceeded" }
        val ordinal = values.size
        values.add(value)
        ordinals[value] = ordinal
        ordinal
    }

    fun text(id: Int): String = values[id]

    fun clear() { ordinals.clear(); values.clear() }
}

internal fun propertyPair(first: Int, second: Int): Long =
    (first.toLong() shl Int.SIZE_BITS) or (second.toLong() and 0xffffffffL)
