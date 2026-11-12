package edu.jhu.cobra.commons.graph.storage

// Pair and complete-tag lookup share the same rows. Null slots terminate probes; deletion closes the probe cluster.
internal class EdgePairIndex(
    private val order: (Int) -> Long = Int::toLong,
    private val onProbe: () -> Unit = {},
) {
    private class Tags(
        val firstTag: String,
        var firstIds: TagAdjacencyIndex.EdgeIds,
        val allIds: TagAdjacencyIndex.EdgeIds = TagAdjacencyIndex.EdgeIds(firstIds.first()),
    ) {
        var others: HashMap<String, TagAdjacencyIndex.EdgeIds>? = null

        fun find(tag: String): Int? = if (tag == firstTag) firstIds.first() else others?.get(tag)?.first()

        fun add(id: Int, tag: String, order: (Int) -> Long) {
            allIds.add(id, order)
            if (tag == firstTag) firstIds = firstIds.add(id, order)
            else {
                val rest = others ?: HashMap<String, TagAdjacencyIndex.EdgeIds>().also { others = it }
                rest[tag] = rest[tag]?.add(id, order) ?: TagAdjacencyIndex.EdgeIds(id)
            }
        }

        fun remove(id: Int, tag: String): Tags? {
            allIds.remove(id)
            if (tag != firstTag) {
                val remaining = others?.get(tag)?.remove(id)
                if (remaining == null) others?.remove(tag) else others?.set(tag, remaining)
                if (others?.isEmpty() == true) others = null
                return this
            }
            val remaining = firstIds.remove(id)
            if (remaining != null) {
                firstIds = remaining
                return this
            }
            val next = others?.entries?.firstOrNull() ?: return null
            val replacement = Tags(next.key, next.value, allIds)
            others?.remove(next.key)
            replacement.others = others?.takeIf { it.isNotEmpty() }
            return replacement
        }
    }

    // A power-of-two table below half occupancy bounds the expected probe count.
    private var keys = LongArray(INITIAL_CAPACITY)
    private var rows = arrayOfNulls<Tags>(INITIAL_CAPACITY)
    private var size = 0

    fun add(id: Int, structure: IStorage.EdgeStructure) {
        if ((size + 1) * 2 > rows.size) resize(rows.size * 2)
        val key = pairKey(structure.src, structure.dst)
        val slot = slot(key)
        val row = rows[slot]
        if (row == null) {
            keys[slot] = key
            rows[slot] = Tags(structure.tag, TagAdjacencyIndex.EdgeIds(id))
            size++
        } else row.add(id, structure.tag, order)
    }

    fun find(src: Int, dst: Int): Int? = rows[slot(pairKey(src, dst))]?.allIds?.first()

    fun find(structure: IStorage.EdgeStructure): Int? =
        rows[slot(pairKey(structure.src, structure.dst))]?.find(structure.tag)

    fun snapshot(src: Int, dst: Int, tag: String?): Set<Int> {
        val row = rows[slot(pairKey(src, dst))] ?: return emptySet()
        return when (tag) {
            null -> row.allIds.snapshot()
            row.firstTag -> row.firstIds.snapshot()
            else -> row.others?.get(tag)?.snapshot().orEmpty()
        }
    }

    fun remove(id: Int, structure: IStorage.EdgeStructure) {
        val slot = slot(pairKey(structure.src, structure.dst))
        val row = rows[slot] ?: return
        rows[slot] = row.remove(id, structure.tag)
        if (rows[slot] != null) return
        size--
        closeCluster(slot)
        if (rows.size > INITIAL_CAPACITY && size * 8 < rows.size) resize(rows.size / 2)
    }

    private fun closeCluster(deleted: Int) {
        var next = (deleted + 1) and (rows.size - 1)
        while (rows[next] != null) {
            val row = checkNotNull(rows[next])
            val key = keys[next]
            rows[next] = null
            val target = slot(key)
            keys[target] = key
            rows[target] = row
            next = (next + 1) and (rows.size - 1)
        }
    }

    private fun slot(key: Long): Int {
        var candidate = hash(key) and (rows.size - 1)
        onProbe()
        while (rows[candidate] != null && keys[candidate] != key) {
            candidate = (candidate + 1) and (rows.size - 1)
            onProbe()
        }
        return candidate
    }

    private fun resize(capacity: Int) {
        val oldKeys = keys
        val oldRows = rows
        keys = LongArray(capacity)
        rows = arrayOfNulls(capacity)
        for (index in oldRows.indices) {
            val row = oldRows[index] ?: continue
            val target = slot(oldKeys[index])
            keys[target] = oldKeys[index]
            rows[target] = row
        }
    }

    fun clear() {
        keys = LongArray(INITIAL_CAPACITY)
        rows = arrayOfNulls(INITIAL_CAPACITY)
        size = 0
    }

    private fun pairKey(src: Int, dst: Int): Long = (src.toLong() shl Int.SIZE_BITS) or (dst.toLong() and LOW_BITS)

    private fun hash(key: Long): Int {
        var mixed = (key xor (key ushr MIX_SHIFT)) * MIX_MULTIPLIER
        mixed = (mixed xor (mixed ushr MIX_SHIFT)) * MIX_MULTIPLIER
        return (mixed xor (mixed ushr MIX_SHIFT)).toInt()
    }

    private companion object {
        // Table geometry, unsigned low-word mask and multiplicative hash mixing, independent of graph content.
        const val INITIAL_CAPACITY = 16
        const val LOW_BITS = 0xffffffffL
        const val MIX_SHIFT = 33
        const val MIX_MULTIPLIER = -49064778989728563L
    }
}

// Immutable frozen encounter ranks. Zero is empty; the low word stores rank + 1, the high word the global ID.
internal class FrozenEdgeOrder {
    private var rows = LongArray(0)

    fun reset(count: Int) {
        var capacity = 1
        while (capacity < count.toLong() * 2) capacity *= 2
        rows = LongArray(capacity)
    }

    fun add(id: Int, rank: Int) {
        rows[slot(id)] = (id.toLong() shl Int.SIZE_BITS) or (rank.toLong() + 1)
    }

    fun rank(id: Int): Long {
        val row = if (rows.isEmpty()) 0 else rows[slot(id)]
        return if (row == 0L) Int.MAX_VALUE.toLong() + 1 + id else (row and LOW_BITS) - 1
    }

    fun clear() {
        rows = LongArray(0)
    }

    private fun slot(id: Int): Int {
        val hash = id * ID_MULTIPLIER
        var slot = (hash xor (hash ushr HASH_SHIFT)) and (rows.size - 1)
        while (rows[slot] != 0L && (rows[slot] ushr Int.SIZE_BITS).toInt() != id) {
            slot = (slot + 1) and (rows.size - 1)
        }
        return slot
    }

    private companion object {
        // Multiplicative hash, high/low-word layout and unsigned low-word mask for Int IDs and ranks.
        const val ID_MULTIPLIER = -1640531527
        const val HASH_SHIFT = 16
        const val LOW_BITS = 0xffffffffL
    }
}
