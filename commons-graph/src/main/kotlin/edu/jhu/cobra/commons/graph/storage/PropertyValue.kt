package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import java.util.Collections

/** Immutable admitted property content, transportable through caller-owned buffers and fixed images. */
public sealed interface PropertyValue {
    /** The registered field identity; null for an unregistered legacy extension. */
    public val key: PropertyKey<*>?

    /** Returns the immutable typed value; rejects another field or content kind. */
    public fun <T : Any> get(key: PropertyKey<T>): T

    /** Returns immutable family identifiers; rejects another family or content kind. */
    public fun members(key: IndexedPropertyKey<*>): List<String>

    /** Creates independent compatibility content without exposing private mutable descendants. */
    public fun snapshot(): IValue

    public companion object {
        /** Captures a registered payload once according to its schema contract. */
        public fun <T : Any> capture(key: PropertyKey<T>, value: T): PropertyValue = key.capture(value)

        /** Captures the membership anchor of one indexed family. */
        public fun membership(key: IndexedPropertyKey<*>, members: List<String>): PropertyValue =
            CapturedMembers(key, members)

        /** Captures an unregistered compatibility value privately. */
        public fun legacy(value: IValue): PropertyValue = LegacyProperty(value.deepCopy())
    }
}

internal class CapturedMembers(
    override val key: IndexedPropertyKey<*>,
    members: List<String>,
) : PropertyValue {
    private val values = Collections.unmodifiableList(ArrayList(members))
    override fun <T : Any> get(key: PropertyKey<T>): T = error("Membership is not a scalar payload")
    override fun members(key: IndexedPropertyKey<*>): List<String> {
        check(this.key.agrees(key)) { "Wrong family for ${key.name}" }
        return values
    }
    override fun snapshot(): IValue = key.indexedCodec.encodeMembers(values)
    override fun equals(other: Any?): Boolean =
        other is CapturedMembers && key.agrees(other.key) && values == other.values
    override fun hashCode(): Int = 31 * key.name.hashCode() + values.hashCode()
}

internal fun CapturedProperty.content(name: String): PropertyValue? = when (this) {
    is LegacyProperty -> this
    is CapturedField<*> -> this
    is CapturedFamily<*> -> content(name)
}

internal fun CapturedProperty.copyContentsInto(name: String, target: MutableMap<String, PropertyValue>) {
    if (this is CapturedFamily<*>) copyContentsInto(target)
    else content(name)?.let { target[name] = it }
}

internal fun CapturedRow.contents(): Map<String, PropertyValue> {
    val result = LinkedHashMap<String, PropertyValue>()
    for ((name, value) in this) value.copyContentsInto(name, result)
    return result
}
