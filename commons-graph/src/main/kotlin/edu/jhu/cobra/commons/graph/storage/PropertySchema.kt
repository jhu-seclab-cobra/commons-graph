package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import kotlin.reflect.KClass

/** Captures one immutable payload and converts its independent compatibility representation. */
public interface PropertyCodec<T : Any> {
    /** Captures caller aliases and validates the complete value before admission. */
    public fun capture(value: T): T

    /** Validates and captures compatibility content; malformed content raises IllegalArgumentException. */
    public fun decode(value: IValue): T

    /** Returns independent mutable compatibility content for the admitted immutable value. */
    public fun encode(value: T): IValue
}

/** Registered scalar identity, runtime type and immutable capture/codec contract. */
public open class PropertyKey<T : Any>(
    public val name: String,
    public val type: KClass<T>,
    internal val codec: PropertyCodec<T>,
) {
    init { require(name.isNotEmpty()) { "A registered field needs a stable name" } }

    internal open fun agrees(other: PropertyKey<*>): Boolean =
        other !is IndexedPropertyKey<*> && name == other.name && type == other.type && codec === other.codec

    internal fun capture(value: T): CapturedField<T> {
        require(type.isInstance(value)) { "Wrong type for $name" }
        val captured = codec.capture(value)
        require(type.isInstance(captured)) { "Wrong captured type for $name" }
        return CapturedField(this, captured)
    }

    internal fun decode(value: IValue): CapturedField<T> {
        val captured = codec.decode(value)
        require(type.isInstance(captured)) { "Wrong decoded type for $name" }
        return CapturedField(this, captured)
    }
}

/** Compatibility conversion of a pair-addressed family and its membership anchor. */
public interface IndexedPropertyCodec<T : Any> : PropertyCodec<T> {
    /** Encodes two opaque coordinates without parsing their meaning. */
    public fun cellName(first: String, second: String): String

    /** Returns coordinates of a valid member name, or null outside this family. */
    public fun cellAddress(name: String): Pair<String, String>?

    /** Validates the anchor and returns its opaque member identifiers. */
    public fun decodeMembers(value: IValue): List<String>

    /** Encodes the selected immutable membership into independent compatibility content. */
    public fun encodeMembers(members: List<String>): IValue
}

/** Registers one family anchor and its addressed cell codec, optionally restricted to one complete edge tag. */
public class IndexedPropertyKey<T : Any>(
    name: String,
    public val memberPrefix: String,
    type: KClass<T>,
    internal val indexedCodec: IndexedPropertyCodec<T>,
    public val edgeTag: String? = null,
) : PropertyKey<T>(name, type, indexedCodec) {
    init {
        require(memberPrefix.isNotEmpty() && !name.startsWith(memberPrefix)) { "Ambiguous family names" }
    }

    internal fun matches(name: String, tag: String?): Boolean =
        tag != null && (edgeTag == null || edgeTag == tag) && (name == this.name || name.startsWith(memberPrefix))

    override fun agrees(other: PropertyKey<*>): Boolean =
        other is IndexedPropertyKey<*> && name == other.name && type == other.type && codec === other.codec &&
            memberPrefix == other.memberPrefix && edgeTag == other.edgeTag
}

/** Fixed registration shared by construction; mutable directory state belongs to each storage instance. */
public class StorageSchema(keys: List<PropertyKey<*>>) {
    internal val keys: Map<String, PropertyKey<*>>

    init {
        val registered = LinkedHashMap<String, PropertyKey<*>>()
        for (key in keys) {
            val previous = registered.putIfAbsent(key.name, key)
            require(previous == null || previous.agrees(key)) { "Conflicting registered field ${key.name}" }
        }
        val families = registered.values.filterIsInstance<IndexedPropertyKey<*>>()
        for (family in families) for (key in registered.values) {
            require(key === family || !key.name.startsWith(family.memberPrefix)) {
                "Registered field ${key.name} overlaps family ${family.name}"
            }
        }
        for (family in families) for (other in families) {
            require(family === other || !family.memberPrefix.startsWith(other.memberPrefix)) {
                "Overlapping cell prefixes ${family.memberPrefix} and ${other.memberPrefix}"
            }
        }
        this.keys = registered.toMap()
    }

    internal fun requireKey(key: PropertyKey<*>) {
        check(keys[key.name]?.agrees(key) == true) { "Field ${key.name} does not belong to this schema" }
    }
}
