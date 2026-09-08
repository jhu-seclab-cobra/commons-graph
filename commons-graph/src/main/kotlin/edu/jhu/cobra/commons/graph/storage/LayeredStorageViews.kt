package edu.jhu.cobra.commons.graph.storage

// Read-only view collections backing LayeredStorageImpl query results.
// Each view resolves lazily against the underlying layer data instead of copying it.

/** Lazy set view translating frozen-local edge IDs to global edge IDs. */
internal class MappedEdgeSet(
    private val localIds: Set<Int>,
    private val localToGlobal: Map<Int, Int>,
    private val globalToLocal: Map<Int, Int>,
) : AbstractSet<Int>() {
    override val size: Int get() = localIds.size

    // Exhaustion NoSuchElementException comes from the delegated iter.next();
    // detekt's static check does not see through the delegation.
    @Suppress("IteratorNotThrowingNoSuchElementException")
    override fun iterator(): Iterator<Int> {
        val iter = localIds.iterator()
        return object : Iterator<Int> {
            override fun hasNext() = iter.hasNext()

            override fun next(): Int {
                val localId = iter.next()
                // A missing translation is a broken ID table, not iterator exhaustion;
                // NoSuchElementException would read as a normal end-of-iteration signal.
                return localToGlobal[localId] ?: error("No global ID for local $localId")
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
