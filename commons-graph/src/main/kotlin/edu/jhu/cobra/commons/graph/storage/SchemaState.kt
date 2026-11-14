package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

// Mutable dictionaries belong to one constructed storage, never to a shared schema declaration.
internal class SchemaState(val schema: StorageSchema) {
    val symbols = PropertySymbols()
    private val indexed = schema.keys.values.filterIsInstance<IndexedPropertyKey<*>>()

    fun prepare(properties: Map<String, IValue?>, tag: String? = null): PreparedProperties {
        val fields = LinkedHashMap<String, CapturedProperty?>()
        val families = LinkedHashMap<IndexedPropertyKey<*>, FamilyPatch<*>>()
        for ((name, value) in properties) {
            val key = schema.keys[name]
            val family = indexed.firstOrNull { it.matches(name, tag) }
            if (family != null) {
                val patch = families.getOrPut(family) { FamilyPatch(family) }
                patch.decode(name, value)
            } else {
                fields[name] = when {
                    value == null -> null
                    key != null && key !is IndexedPropertyKey<*> -> key.decode(value.deepCopy())
                    else -> LegacyProperty(value.deepCopy())
                }
            }
        }
        return PreparedProperties(fields, families.values.toList())
    }

    fun prepareContents(properties: Map<String, PropertyValue?>, tag: String? = null): PreparedProperties {
        val fields = LinkedHashMap<String, CapturedProperty?>()
        val families = LinkedHashMap<IndexedPropertyKey<*>, FamilyPatch<*>>()
        for ((name, value) in properties) {
            val family = indexed.firstOrNull { it.matches(name, tag) }
            if (family != null) {
                families.getOrPut(family) { FamilyPatch(family) }.accept(name, value)
                continue
            }
            when (value) {
                null -> fields[name] = null
                is CapturedField<*> -> {
                    schema.requireKey(value.key)
                    require(name == value.key.name && value.key !is IndexedPropertyKey<*>) { "Wrong field name $name" }
                    fields[name] = value
                }
                is LegacyProperty -> fields[name] = schema.keys[name]?.takeUnless { it is IndexedPropertyKey<*> }
                    ?.decode(value.value) ?: value
                is CapturedMembers -> error("Membership outside its family")
            }
        }
        return PreparedProperties(fields, families.values.toList())
    }

    fun captureContents(properties: Map<String, IValue?>, tag: String?): Map<String, PropertyValue?> {
        val result = LinkedHashMap<String, PropertyValue?>()
        for ((name, value) in properties) {
            if (value == null) { result[name] = null; continue }
            val family = indexed.firstOrNull { it.matches(name, tag) }
            result[name] = when {
                family != null -> captureFamilyValue(family, name, value)
                schema.keys[name] != null && schema.keys[name] !is IndexedPropertyKey<*> ->
                    schema.keys.getValue(name).decode(value.deepCopy())
                else -> PropertyValue.legacy(value)
            }
        }
        return result
    }

    private fun <T : Any> captureFamilyValue(key: IndexedPropertyKey<T>, name: String, value: IValue): PropertyValue {
        if (name == key.name) return CapturedMembers(key, key.indexedCodec.decodeMembers(value))
        val (first, second) = requireNotNull(key.indexedCodec.cellAddress(name)) { "Invalid cell name $name" }
        val captured = key.decode(value.deepCopy())
        key.indexedCodec.validate(first, second, captured.value)
        return captured
    }
}

internal class PreparedProperties(
    private val fields: Map<String, CapturedProperty?>,
    private val families: List<FamilyPatch<*>>,
) {
    fun apply(current: (String) -> CapturedProperty?, symbols: PropertySymbols): Map<String, CapturedProperty?> {
        val result = LinkedHashMap(fields)
        for (patch in families) result[patch.key.name] = patch.apply(current(patch.key.name), symbols)
        return result
    }
}

internal class FamilyPatch<T : Any>(val key: IndexedPropertyKey<T>) {
    private var anchorWritten = false
    private var members: List<String>? = null
    private val cells = ArrayList<Triple<String, String, T?>>()

    fun decode(name: String, value: IValue?) {
        if (name == key.name) {
            anchorWritten = true
            members = value?.let(key.indexedCodec::decodeMembers)
            return
        }
        val (first, second) = requireNotNull(key.indexedCodec.cellAddress(name)) { "Invalid cell name $name" }
        val captured = value?.let { key.decode(it.deepCopy()).value }
        if (captured != null) key.indexedCodec.validate(first, second, captured)
        cells.add(Triple(first, second, captured))
    }

    fun accept(name: String, value: PropertyValue?) {
        if (value is LegacyProperty) { decode(name, value.value); return }
        if (name == key.name) {
            anchorWritten = true
            members = value?.members(key)
            return
        }
        val (first, second) = requireNotNull(key.indexedCodec.cellAddress(name)) { "Invalid cell name $name" }
        val payload = value?.get(key)
        if (payload != null) key.indexedCodec.validate(first, second, payload)
        cells.add(Triple(first, second, payload))
    }

    fun apply(current: CapturedProperty?, symbols: PropertySymbols): CapturedFamily<T> {
        val family = key.family(current) ?: CapturedFamily(key, symbols)
        if (anchorWritten) family.setMembers(members)
        for ((first, second, value) in cells) family.set(first, second, value, false)
        return family
    }
}
