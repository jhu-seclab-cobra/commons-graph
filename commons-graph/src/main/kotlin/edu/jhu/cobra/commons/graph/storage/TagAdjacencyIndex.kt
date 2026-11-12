package edu.jhu.cobra.commons.graph.storage

// The storage owns these IDs under its existing guard. Singleton memberships share one allocation per edge.
internal class TagAdjacencyIndex(private val tagGroup: (String) -> String = { it }) {
    private data class GroupKey(val node: Int, val group: String)

    internal sealed interface EdgeIds {
        fun add(id: Int): EdgeIds
        fun remove(id: Int): EdgeIds?
        fun snapshot(): Set<Int>
        fun first(): Int
    }

    internal class Single(private val id: Int) : EdgeIds {
        override fun add(id: Int): EdgeIds = if (this.id == id) this else Multiple(linkedSetOf(this.id, id))
        override fun remove(id: Int): EdgeIds? = if (this.id == id) null else this
        override fun snapshot(): Set<Int> = setOf(id)
        override fun first(): Int = id
    }

    private class Multiple(private val ids: LinkedHashSet<Int>) : EdgeIds {
        override fun add(id: Int): EdgeIds = apply { ids.add(id) }
        override fun remove(id: Int): EdgeIds? {
            ids.remove(id)
            return when (ids.size) {
                0 -> null
                1 -> Single(ids.first())
                else -> this
            }
        }
        override fun snapshot(): Set<Int> = LinkedHashSet(ids)
        override fun first(): Int = ids.first()
    }

    private val incoming = HashMap<GroupKey, EdgeIds>()
    private val outgoing = HashMap<GroupKey, EdgeIds>()

    fun add(id: Int, structure: IStorage.EdgeStructure) {
        val group = tagGroup(structure.tag)
        val singleton = Single(id)
        addTo(incoming, GroupKey(structure.dst, group), id, singleton)
        addTo(outgoing, GroupKey(structure.src, group), id, singleton)
    }

    private fun <K> addTo(index: MutableMap<K, EdgeIds>, key: K, id: Int, singleton: EdgeIds) {
        index[key] = index[key]?.add(id) ?: singleton
    }

    fun remove(id: Int, structure: IStorage.EdgeStructure) {
        val group = tagGroup(structure.tag)
        removeFrom(incoming, GroupKey(structure.dst, group), id)
        removeFrom(outgoing, GroupKey(structure.src, group), id)
    }

    private fun <K> removeFrom(index: MutableMap<K, EdgeIds>, key: K, id: Int) {
        val remaining = index[key]?.remove(id)
        if (remaining == null) index.remove(key) else index[key] = remaining
    }

    fun incoming(node: Int, group: String): Set<Int> = incoming[GroupKey(node, group)]?.snapshot().orEmpty()

    fun outgoing(node: Int, group: String): Set<Int> = outgoing[GroupKey(node, group)]?.snapshot().orEmpty()

    fun clear() {
        incoming.clear()
        outgoing.clear()
    }
}
