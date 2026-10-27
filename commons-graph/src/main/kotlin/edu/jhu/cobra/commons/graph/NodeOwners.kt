package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcMultipleGraph.Companion.PROP_OWNERS
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal

/**
 * The owner marks one graph keeps on storage node rows: the [PROP_OWNERS] set of a row holds the ID of every
 * graph that registered the row. A row with no mark belongs to no graph.
 */
internal class NodeOwners(
    private val storage: IStorage,
    private val graphId: String,
) {
    /** The owner set of a row only this graph has registered. */
    fun initial(): SetVal = SetVal(setOf(StrVal(graphId)))

    /** Whether the row [storageId] names this graph in its owner set. */
    fun holds(storageId: Int): Boolean = ownersOf(storageId).contains(StrVal(graphId))

    /** Adds this graph to the owner set of the row [storageId]; writes only when the graph is missing from it. */
    fun mark(storageId: Int) {
        val existing = ownersOf(storageId)
        if (existing.contains(StrVal(graphId))) return
        storage.setNodeProperties(storageId, mapOf(PROP_OWNERS to SetVal(existing.core + StrVal(graphId))))
    }

    private fun ownersOf(storageId: Int): SetVal = storage.getNodeProperty(storageId, PROP_OWNERS) as? SetVal ?: SetVal()

    companion object {
        /** The owner law: the base owners plus every owner a writer added to its own before-image. */
        val LAW: PropertyMergeLaw = PropertyMergeLaw(::unionOwners)

        private fun unionOwners(
            base: IValue?,
            changes: Collection<PropertyChange>,
        ): IValue? {
            val added = changes.flatMapTo(HashSet()) { membersOf(it.after) - membersOf(it.before) }
            val created = base == null && changes.any { it.before == null && it.after != null }
            return if (added.isEmpty() && !created) base else SetVal(membersOf(base) + added)
        }

        private fun membersOf(value: IValue?): Set<IValue> =
            when (value) {
                null -> emptySet()
                is SetVal -> value.core
                else -> throw IllegalArgumentException("owners property is not a set: $value")
            }
    }
}
