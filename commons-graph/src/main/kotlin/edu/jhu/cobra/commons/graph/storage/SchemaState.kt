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
        cells.add(Triple(first, second, value?.let { key.decode(it.deepCopy()).value }))
    }

    fun apply(current: CapturedProperty?, symbols: PropertySymbols): CapturedFamily<T> {
        val family = key.family(current) ?: CapturedFamily(key, symbols)
        if (anchorWritten) family.setMembers(members)
        for ((first, second, value) in cells) family.set(first, second, value, false)
        return family
    }
}
