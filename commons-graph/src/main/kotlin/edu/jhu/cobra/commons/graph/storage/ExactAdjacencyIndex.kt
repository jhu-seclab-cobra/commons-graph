package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.storage.IStorage.EdgeStructure
import edu.jhu.cobra.commons.value.IValue

/** Physical access to existing adjacency rows for one immutable projection; the owning storage guards mutations. */
internal class ExactAdjacencyIndex(val projection: AdjacencyProjection) {
    internal data class Key(val source: Int, val kind: IValue, val value: IValue)

    internal data class Member(val key: Key, val target: Int)

    private val buckets = HashMap<Key, LinkedHashSet<Int>>()
    private val members = HashMap<Int, Member>()
    private val targets = HashMap<Int, Int>()

    val size: Int get() = members.size

    fun prepare(structure: EdgeStructure, property: (Int, String) -> IValue?): Member? {
        if (structure.tag != projection.edgeTag) return null
        val kind = requireNotNull(property(structure.dst, projection.kindProperty)) { "Missing projected kind" }
        val key = requireNotNull(property(structure.dst, projection.keyProperty)) { "Missing projected key" }
        return Member(Key(structure.src, kind.deepCopy(), key.deepCopy()), structure.dst)
    }

    fun record(id: Int, member: Member?) {
        if (member == null) return
        check(id !in members) { "Adjacency row $id is already indexed" }
        members[id] = member
        buckets.getOrPut(member.key, ::LinkedHashSet) += id
        targets[member.target] = targets.getOrDefault(member.target, 0) + 1
    }

    fun select(source: Int, kind: IValue, key: IValue): Set<Int> =
        buckets[Key(source, kind, key)]?.toSet().orEmpty()

    fun existing(member: Member): Int? {
        val candidates = buckets[member.key].orEmpty()
        require(candidates.all { members.getValue(it).target == member.target }) {
            "Projected key is already attached to a different target"
        }
        return candidates.firstOrNull()
    }

    fun validateChange(id: Int, changes: Map<String, IValue?>, property: (Int, String) -> IValue?) {
        if (id !in targets) return
        for (field in listOf(projection.kindProperty, projection.keyProperty)) {
            require(field !in changes || changes[field] == property(id, field)) {
                "An attached projection field cannot change: $field"
            }
        }
    }

    fun remove(id: Int) {
        val member = members.remove(id) ?: return
        val bucket = buckets.getValue(member.key)
        bucket.remove(id)
        if (bucket.isEmpty()) buckets.remove(member.key)
        val remaining = targets.getValue(member.target) - 1
        if (remaining == 0) targets.remove(member.target) else targets[member.target] = remaining
    }

    fun clear() {
        buckets.clear()
        members.clear()
        targets.clear()
    }
}
