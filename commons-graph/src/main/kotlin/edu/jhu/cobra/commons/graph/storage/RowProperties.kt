package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Row property store of one entity kind: each entity with properties holds one exactly sized
 * array of its own names and values, `[name0, value0, name1, value1, ...]`, in the order the names
 * were first set. An entity without properties has no row. Names are kept once per store, so rows
 * share one String per name.
 *
 * A row read, an emptiness check and an entity removal touch only that entity's row; a named read
 * scans the row's names.
 *
 * Not thread-safe; the owning storage guards every call.
 *
 * @param internKey maps a new property name to the instance the store keeps (identity by default).
 */
internal class RowProperties(
    private val internKey: (String) -> String = { it },
) {
    private val names = HashMap<String, String>()
    private val rows = HashMap<Int, Array<Any?>>()

    /** The value of property [name] of entity [id], or null. */
    operator fun get(
        id: Int,
        name: String,
    ): IValue? {
        val row = rows[id] ?: return null
        val at = indexOf(row, name, row.size)
        return if (at < 0) null else row[at + 1] as IValue
    }

    /** True when entity [id] holds property [name]. */
    fun contains(
        id: Int,
        name: String,
    ): Boolean {
        val row = rows[id] ?: return false
        return indexOf(row, name, row.size) >= 0
    }

    /** True when entity [id] holds at least one property. */
    fun holdsAny(id: Int): Boolean = rows.containsKey(id)

    /** Puts every property of entity [id] into [target], in first-set order. */
    fun copyInto(
        id: Int,
        target: MutableMap<String, IValue>,
    ) {
        val row = rows[id] ?: return
        for (i in row.indices step 2) target[row[i] as String] = row[i + 1] as IValue
    }

    /**
     * Applies property updates for [id]. Non-null values are set; null values delete the property.
     * Takes O(names of the row) per updated name and one row copy when a name is added or deleted.
     */
    fun set(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        if (properties.isEmpty()) return
        val original = rows[id]
        var row = original
        var added = 0
        for ((name, value) in properties) {
            val at = if (row == null) -1 else indexOf(row, name, row.size)
            when {
                value == null -> if (at >= 0) row = without(row!!, at)
                at >= 0 -> row!![at + 1] = value
                else -> added++
            }
        }
        if (added > 0) row = withAdded(row, properties, added)
        if (row === original) return
        if (row == null || row.isEmpty()) rows.remove(id) else rows[id] = row
    }

    /** Snapshots all properties of entity [id] into a plain map. */
    fun collect(id: Int): Map<String, IValue> {
        val row = rows[id] ?: return HashMap()
        val result = HashMap<String, IValue>(row.size)
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

    /**
     * Throws [IllegalStateException] when a row is empty, lists a name twice, or holds a name
     * instance other than the store's.
     */
    fun checkRows() {
        for ((id, row) in rows) {
            check(row.isNotEmpty()) { "Entity $id has an empty row" }
            val rowNames = row.indices.step(2).map { row[it] as String }
            check(rowNames.size == rowNames.toSet().size) { "Entity $id lists a name twice: $rowNames" }
            for (name in rowNames) check(names[name] === name) { "Entity $id holds a name the store does not keep" }
        }
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

    // [row] grown by the [added] non-null names of [properties] it does not hold.
    private fun withAdded(
        row: Array<Any?>?,
        properties: Map<String, IValue?>,
        added: Int,
    ): Array<Any?> {
        val old = row?.size ?: 0
        val result = if (row == null) arrayOfNulls(2 * added) else row.copyOf(old + 2 * added)
        var next = old
        for ((name, value) in properties) {
            if (value == null || indexOf(result, name, old) >= 0) continue
            result[next++] = names.getOrPut(name) { internKey(name) }
            result[next++] = value
        }
        return result
    }
}

/**
 * Lazy read-only map view over one entity's properties in a [RowProperties] store.
 * Entries are materialized once on first access and cached; size and emptiness reuse the cache
 * when present.
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
