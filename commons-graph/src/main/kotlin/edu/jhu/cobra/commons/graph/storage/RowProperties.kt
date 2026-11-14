package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Property rows of one entity kind, shared by [NativeStorageImpl], [NativeConcurStorageImpl], and the
 * active layer of [LayeredStorageImpl].
 *
 * Each entity with properties holds one exactly sized array of its own names and values,
 * `[name0, value0, name1, value1, ...]`, in the order the names were first set; a name deleted and
 * set again moves to the end. An entity without properties has no row. Every read and write of an
 * entity looks up that entity's row once and never visits another row; a named read scans the
 * row's names.
 *
 * The store keeps one instance per property name, so rows share one String per name. The name pool
 * keeps every name ever set until [clear], so it holds at most the distinct property names of the
 * store.
 *
 * Not thread-safe; the owning storage guards every call, the name pool included.
 *
 * @param rows the row table, keyed by entity id (a test passes a counting map).
 */
internal class RowProperties(
    private val rows: HashMap<Int, Array<Any?>> = HashMap(),
) {
    private val names = HashMap<String, String>()

    /** The value of property [name] of entity [id], or null. */
    operator fun get(
        id: Int,
        name: String,
    ): IValue? {
        val row = rows[id] ?: return null
        val at = indexOf(row, name, row.size)
        if (at >= 0) return compatibility(row[at + 1], name)
        for (i in row.indices step 2) {
            val family = row[i + 1] as? CapturedFamily<*> ?: continue
            if (name.startsWith(family.key.memberPrefix)) return family.compatibility(name)
        }
        return null
    }

    /** True when entity [id] holds property [name]. */
    fun contains(
        id: Int,
        name: String,
    ): Boolean {
        val row = rows[id] ?: return false
        val at = indexOf(row, name, row.size)
        if (at >= 0) return (row[at + 1] as? CapturedProperty)?.isPresent(name) ?: true
        for (i in row.indices step 2) {
            val family = row[i + 1] as? CapturedFamily<*> ?: continue
            if (name.startsWith(family.key.memberPrefix)) return family.isPresent(name)
        }
        return false
    }

    /** True when entity [id] holds at least one property. */
    fun holdsAny(id: Int): Boolean = rows.containsKey(id)

    /** Puts every property of entity [id] into [target], in first-set order. */
    fun copyInto(
        id: Int,
        target: MutableMap<String, IValue>,
    ) {
        val row = rows[id] ?: return
        for (i in row.indices step 2) {
            val name = row[i] as String
            val value = row[i + 1]
            if (value is CapturedProperty) value.copyInto(name, target) else target[name] = value as IValue
        }
    }

    /**
     * Applies property updates for [id]. Non-null values are set; null values delete the property.
     * Takes O(properties of the row) per updated name and one row copy per deleted name and per
     * call that adds a name.
     */
    fun set(
        id: Int,
        properties: Map<String, IValue?>,
    ) = setValues(id, properties)

    fun setCaptured(id: Int, properties: Map<String, CapturedProperty?>) = setValues(id, properties)

    fun capturedProperty(id: Int, name: String): CapturedProperty? {
        val row = rows[id] ?: return null
        val at = indexOf(row, name, row.size)
        if (at < 0) return null
        val value = row[at + 1]
        return value as? CapturedProperty ?: LegacyProperty(value as IValue)
    }

    fun content(id: Int, name: String): PropertyValue? {
        val row = rows[id] ?: return null
        val at = indexOf(row, name, row.size)
        if (at >= 0) {
            val value = row[at + 1]
            return if (value is CapturedProperty) value.content(name) else LegacyProperty(value as IValue)
        }
        for (i in row.indices step 2) {
            val family = row[i + 1] as? CapturedFamily<*> ?: continue
            if (name.startsWith(family.key.memberPrefix)) return family.content(name)
        }
        return null
    }

    fun captured(id: Int): CapturedRow {
        val row = rows[id] ?: return emptyMap()
        val result = LinkedHashMap<String, CapturedProperty>()
        for (i in row.indices step 2) {
            val value = row[i + 1]
            result[row[i] as String] = value as? CapturedProperty ?: LegacyProperty(value as IValue)
        }
        return result
    }

    private fun <T> setValues(id: Int, properties: Map<String, T?>) {
        if (properties.isEmpty()) return
        val original = rows[id]
        var row = original
        var added = 0
        for ((name, value) in properties) {
            val at = if (row == null) -1 else indexOf(row, name, row.size)
            when {
                value == null -> if (at >= 0) row = without(checkNotNull(row), at)
                at >= 0 -> checkNotNull(row)[at + 1] = if (value is LegacyProperty) value.value else value
                else -> added++
            }
        }
        if (added > 0) row = withAdded(row, properties, added)
        if (row === original) return
        if (row == null || row.isEmpty()) rows.remove(id) else rows[id] = row
    }

    /** Snapshots all properties of entity [id] into a plain map, in first-set order. */
    fun collect(id: Int): Map<String, IValue> {
        val result = LinkedHashMap<String, IValue>()
        copyInto(id, result)
        return result
    }

    /** Lazy read-only view of the properties of entity [id]. */
    fun view(id: Int): Map<String, IValue> = RowViewMap(id, this)

    /** Removes every property of entity [id]. */
    fun remove(id: Int) {
        rows.remove(id)
    }

    fun clear() {
        rows.clear()
        names.clear()
    }

    // Index of [name] among the names of [row] before [end], or -1.
    private fun indexOf(
        row: Array<Any?>,
        name: String,
        end: Int,
    ): Int {
        for (i in 0 until end step 2) if (row[i] == name) return i
        return -1
    }

    private fun without(
        row: Array<Any?>,
        at: Int,
    ): Array<Any?> {
        val result = arrayOfNulls<Any?>(row.size - 2)
        System.arraycopy(row, 0, result, 0, at)
        System.arraycopy(row, at + 2, result, at, row.size - at - 2)
        return result
    }

    // [row] grown by the [added] names with non-null values in [properties] that it does not hold.
    private fun <T> withAdded(
        row: Array<Any?>?,
        properties: Map<String, T?>,
        added: Int,
    ): Array<Any?> {
        val old = row?.size ?: 0
        val result = if (row == null) arrayOfNulls(2 * added) else row.copyOf(old + 2 * added)
        var next = old
        for ((name, value) in properties) {
            if (value == null || indexOf(result, name, old) >= 0) continue
            result[next++] = names.getOrPut(name) { name }
            result[next++] = if (value is LegacyProperty) value.value else value
        }
        return result
    }

    private fun compatibility(value: Any?, name: String): IValue? =
        if (value is CapturedProperty) value.compatibility(name) else value as IValue
}

/**
 * Lazy read-only map view over one entity's properties in a [RowProperties] store.
 * `get` and `containsKey` read the row on each call; entries are materialized once on first access,
 * in first-set order, and cached; size and emptiness reuse the cache when present.
 */
internal class RowViewMap(
    private val entityId: Int,
    private val rows: RowProperties,
) : AbstractMap<String, IValue>() {
    private var cachedEntries: Set<Map.Entry<String, IValue>>? = null

    override val entries: Set<Map.Entry<String, IValue>>
        get() {
            cachedEntries?.let { return it }
            val result = LinkedHashMap<String, IValue>()
            rows.copyInto(entityId, result)
            return result.entries.also { cachedEntries = it }
        }

    override fun get(key: String): IValue? = rows[entityId, key]

    override fun containsKey(key: String): Boolean = rows.contains(entityId, key)

    override val size: Int get() = entries.size

    override fun isEmpty(): Boolean {
        cachedEntries?.let { return it.isEmpty() }
        return !rows.holdsAny(entityId)
    }
}
