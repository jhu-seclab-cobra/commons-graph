package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

// Read-only view collections backing LayeredStorageImpl query results.
// Each view resolves lazily against the underlying layer data instead of copying it.

/** Lazy map view over one entity's columnar properties in the active layer. */
internal class ActiveColumnViewMap(
    private val entityId: Int,
    private val columns: HashMap<String, HashMap<Int, IValue>>,
) : AbstractMap<String, IValue>() {
    private var cachedEntries: Set<Map.Entry<String, IValue>>? = null

    override val entries: Set<Map.Entry<String, IValue>>
        get() {
            cachedEntries?.let { return it }
            val result = LinkedHashMap<String, IValue>()
            for ((colName, col) in columns) {
                val v = col[entityId] ?: continue
                result[colName] = v
            }
            return result.entries.also { cachedEntries = it }
        }

    override fun get(key: String): IValue? = columns[key]?.get(entityId)

    override fun containsKey(key: String): Boolean = columns[key]?.containsKey(entityId) == true

    override val size: Int get() = entries.size

    override fun isEmpty(): Boolean {
        for (col in columns.values) {
            if (col.containsKey(entityId)) return false
        }
        return true
    }
}

/** Lazy set view translating frozen-local edge IDs to global edge IDs. */
internal class MappedEdgeSet(
    private val localIds: Set<Int>,
    private val localToGlobal: Map<Int, Int>,
    private val globalToLocal: Map<Int, Int>,
) : AbstractSet<Int>() {
    override val size: Int get() = localIds.size

    override fun iterator(): Iterator<Int> {
        val iter = localIds.iterator()
        return object : Iterator<Int> {
            override fun hasNext() = iter.hasNext()

            override fun next(): Int {
                val localId = iter.next()
                return localToGlobal[localId] ?: throw NoSuchElementException("No global ID for local $localId")
            }
        }
    }

    override fun contains(element: Int): Boolean {
        val localId = globalToLocal[element] ?: return false
        return localId in localIds
    }

    override fun isEmpty(): Boolean = localIds.isEmpty()
}

/** Lazy union view over two sets; duplicates between [first] and [second] count once. */
internal class UnionSet<E>(
    private val first: Set<E>,
    private val second: Set<E>,
) : AbstractSet<E>() {
    override val size: Int
        get() {
            if (second.isEmpty()) return first.size
            if (first.isEmpty()) return second.size
            var count = first.size
            for (e in second) {
                if (e !in first) count++
            }
            return count
        }

    override fun iterator(): Iterator<E> =
        iterator {
            yieldAll(first)
            for (e in second) {
                if (e !in first) yield(e)
            }
        }

    override fun contains(element: E): Boolean = first.contains(element) || second.contains(element)

    override fun isEmpty(): Boolean = first.isEmpty() && second.isEmpty()
}
