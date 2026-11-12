package edu.jhu.cobra.commons.graph.storage

// The storage guards these memberships with the same lock as its physical adjacency.
internal class TagAdjacencyIndex(
    private val tagGroup: (String) -> String = { it },
    private val incomingOrder: (Int) -> Int = { it },
    private val outgoingOrder: (Int) -> Int = { it },
    onRead: (Int) -> Unit = {},
) {
    private val incoming = GroupTable(onRead)
    private val outgoing = GroupTable(onRead)

    fun add(id: Int, structure: IStorage.EdgeStructure) {
        val group = tagGroup(structure.tag)
        incoming.add(structure.dst, group, id)
        outgoing.add(structure.src, group, id)
    }

    fun addIncoming(id: Int, structure: IStorage.EdgeStructure) {
        incoming.add(structure.dst, tagGroup(structure.tag), id)
    }

    fun addOutgoing(id: Int, structure: IStorage.EdgeStructure) {
        outgoing.add(structure.src, tagGroup(structure.tag), id)
    }

    fun remove(id: Int, structure: IStorage.EdgeStructure) {
        val group = tagGroup(structure.tag)
        incoming.remove(structure.dst, group, id)
        outgoing.remove(structure.src, group, id)
    }

    fun incoming(node: Int, group: String): Set<Int> = incoming.snapshot(node, group)

    fun outgoing(node: Int, group: String): Set<Int> = outgoing.snapshot(node, group)

    fun incoming(node: Int, groups: Set<String>): Set<Int> =
        mergeEdgeIds(groups.map { incoming.snapshot(node, it) }, incomingOrder)

    fun outgoing(node: Int, groups: Set<String>): Set<Int> =
        mergeEdgeIds(groups.map { outgoing.snapshot(node, it) }, outgoingOrder)

    fun clear() {
        incoming.clear()
        outgoing.clear()
    }

    // One primitive ID lives inline in the table. Only a second membership allocates an array.
    private class GroupTable(private val onRead: (Int) -> Unit) {
        private var nodes = IntArray(INITIAL_CAPACITY)
        private var groups = arrayOfNulls<String>(INITIAL_CAPACITY)
        private var firstIds = IntArray(INITIAL_CAPACITY)
        private var moreIds = arrayOfNulls<IntArray>(INITIAL_CAPACITY)
        private var size = 0

        fun add(node: Int, group: String, id: Int) {
            if ((size + 1) * 2 > groups.size) resize(groups.size * 2)
            val slot = slot(node, group)
            if (groups[slot] == null) {
                nodes[slot] = node
                groups[slot] = group
                firstIds[slot] = id
                size++
                return
            }
            moreIds[slot] = append(moreIds[slot], id)
        }

        fun remove(node: Int, group: String, id: Int) {
            val slot = slot(node, group)
            if (groups[slot] == null) return
            val more = moreIds[slot]
            if (firstIds[slot] != id) {
                if (more != null) removeFrom(more, id)
            } else if (more != null && more[0] > 0) {
                firstIds[slot] = more[1]
                removeFrom(more, more[1])
            } else {
                groups[slot] = null
                size--
                closeCluster(slot)
            }
            if (more != null && more[0] == 0) moreIds[slot] = null
            if (groups.size > INITIAL_CAPACITY && size * 8 < groups.size) resize(groups.size / 2)
        }

        fun snapshot(node: Int, group: String): Set<Int> {
            val slot = slot(node, group)
            if (groups[slot] == null) return emptySet()
            val result = LinkedHashSet<Int>()
            onRead(firstIds[slot])
            result.add(firstIds[slot])
            val more = moreIds[slot] ?: return result
            for (index in 1..more[0]) {
                onRead(more[index])
                result.add(more[index])
            }
            return result
        }

        private fun closeCluster(deleted: Int) {
            var next = (deleted + 1) and (groups.size - 1)
            while (groups[next] != null) {
                val group = checkNotNull(groups[next])
                groups[next] = null
                move(next, slot(nodes[next], group), group)
                next = (next + 1) and (groups.size - 1)
            }
        }

        private fun move(from: Int, to: Int, group: String) {
            nodes[to] = nodes[from]
            groups[to] = group
            firstIds[to] = firstIds[from]
            moreIds[to] = moreIds[from]
            if (from != to) moreIds[from] = null
        }

        private fun slot(node: Int, group: String): Int {
            val hash = node * NODE_MULTIPLIER + group.hashCode()
            var candidate = (hash xor (hash ushr HASH_SHIFT)) and (groups.size - 1)
            while (groups[candidate] != null && (nodes[candidate] != node || groups[candidate] != group)) {
                candidate = (candidate + 1) and (groups.size - 1)
            }
            return candidate
        }

        private fun resize(capacity: Int) {
            val oldNodes = nodes
            val oldGroups = groups
            val oldFirst = firstIds
            val oldMore = moreIds
            nodes = IntArray(capacity)
            groups = arrayOfNulls(capacity)
            firstIds = IntArray(capacity)
            moreIds = arrayOfNulls(capacity)
            for (index in oldGroups.indices) {
                val group = oldGroups[index] ?: continue
                val target = slot(oldNodes[index], group)
                nodes[target] = oldNodes[index]
                groups[target] = group
                firstIds[target] = oldFirst[index]
                moreIds[target] = oldMore[index]
            }
        }

        fun clear() {
            nodes = IntArray(INITIAL_CAPACITY)
            groups = arrayOfNulls(INITIAL_CAPACITY)
            firstIds = IntArray(INITIAL_CAPACITY)
            moreIds = arrayOfNulls(INITIAL_CAPACITY)
            size = 0
        }
    }

    internal class EdgeIds(private var firstId: Int) {
        private var more: IntArray? = null

        fun add(id: Int): EdgeIds = apply {
            // Ordinary IDs increase. A revert can reinsert an earlier frozen ID at its original position.
            if (id < firstId) {
                more = insert(more, firstId)
                firstId = id
            } else more = insert(more, id)
        }

        fun remove(id: Int): EdgeIds? {
            val rest = more
            if (firstId != id) {
                if (rest != null) removeFrom(rest, id)
            } else {
                if (rest == null || rest[0] == 0) return null
                firstId = rest[1]
                removeFrom(rest, firstId)
            }
            if (rest != null && rest[0] == 0) more = null
            return this
        }

        fun snapshot(): Set<Int> {
            val result = linkedSetOf(firstId)
            val rest = more ?: return result
            for (index in 1..rest[0]) result.add(rest[index])
            return result
        }

        fun first(): Int = firstId
    }

    private companion object {
        // Power-of-two tables stay below half occupancy; arrays store their count in cell zero.
        const val INITIAL_CAPACITY = 16
        const val NODE_MULTIPLIER = -1640531527
        const val HASH_SHIFT = 16

        fun append(previous: IntArray?, id: Int): IntArray {
            val count = previous?.get(0) ?: 0
            val ids = when {
                previous == null -> IntArray(4)
                count + 1 == previous.size -> previous.copyOf(previous.size * 2)
                else -> previous
            }
            ids[0] = count + 1
            ids[count + 1] = id
            return ids
        }

        fun insert(previous: IntArray?, id: Int): IntArray {
            val ids = append(previous, id)
            var index = ids[0]
            while (index > 1 && ids[index - 1] > id) {
                ids[index] = ids[index - 1]
                index--
            }
            ids[index] = id
            return ids
        }

        fun removeFrom(ids: IntArray, id: Int) {
            for (index in 1..ids[0]) {
                if (ids[index] != id) continue
                ids.copyInto(ids, index, index + 1, ids[0] + 1)
                ids[0]--
                return
            }
        }
    }
}

// Each of the fixed number of selected groups contributes one head; no unselected ID is read.
internal fun mergeEdgeIds(selected: List<Set<Int>>, order: (Int) -> Int): Set<Int> {
    val iterators = selected.map { it.iterator() }
    val heads = iterators.map { if (it.hasNext()) it.next() else null }.toMutableList()
    val result = LinkedHashSet<Int>()
    while (true) {
        val index = heads.indices.filter { heads[it] != null }.minByOrNull { order(checkNotNull(heads[it])) } ?: break
        result.add(checkNotNull(heads[index]))
        heads[index] = if (iterators[index].hasNext()) iterators[index].next() else null
    }
    return result
}
