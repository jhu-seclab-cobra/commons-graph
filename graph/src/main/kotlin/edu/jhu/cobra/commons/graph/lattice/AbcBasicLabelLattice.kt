package edu.jhu.cobra.commons.graph.lattice

import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.getTypeProp
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.getMeta
import edu.jhu.cobra.commons.graph.storage.setMeta
import edu.jhu.cobra.commons.value.*

/**
 * An abstract base class implementing `ILabelLattice`, providing core mechanisms for managing label hierarchies
 * and visibility tracking in a partially ordered set (poset) for graph edges.
 *
 * This class extends `ILabelLattice` by adding:
 * - **Change tracking**: Monitors label modifications associated with graph edges.
 * - **Caching**: Stores comparison results to optimize hierarchical label comparisons.
 * - **Storage support**: Serializes and deserializes the lattice structure,
 *     enabling the persistence of label hierarchies and edge associations.
 *
 * Subclasses can leverage these foundational mechanisms to build custom lattice structures, with access to efficient
 * change tracking and serialization capabilities.
 *
 * @see ILabelLattice for primary label operations and properties within a lattice structure.
 */
abstract class AbcBasicLabelLattice : ILabelLattice {

    private val changeRecorder = mutableMapOf<Label, Set<EdgeID>>()
    private val queryCache = mutableMapOf<Pair<Label, Label>, Int?>()

    override var Label.changes: Set<EdgeID>
        get() = changeRecorder[this].orEmpty()
        set(value) {
            changeRecorder[this] = value.toMutableSet()
        }

    override var AbcEdge.labels: Set<Label>
        get() {
            val labelList = getTypeProp<ListVal>(name = "labels")?.core.orEmpty()
            return labelList.map { Label(core = it.core.toString()) }.toSet()
        }
        set(values) {
            val labelList = getTypeProp<ListVal>(name = "labels")?.core.orEmpty()
            val curLabels = labelList.map { Label(core = it.core.toString()) }.toSet()
            (curLabels - values).forEach { delete -> delete.changes -= id }
            (values - curLabels).forEach { newAdd -> newAdd.changes += id }
            setProp(name = "labels", value = values.map { it.core }.listVal)
        }

    override fun Label.compareTo(other: Label): Int? {
        if (this == other) return 0
        if (this == Label.SUPREMUM || other == Label.INFIMUM) return 1
        if (other == Label.SUPREMUM || this == Label.INFIMUM) return -1
        if (this to other in queryCache) return queryCache[this to other]
        if (other to this in queryCache) return queryCache[other to this]?.let { -it }
        other.ancestors.forEach { label ->
            if (label != other) return@forEach
            queryCache[this to other] = -1
            return -1 // a path from the `other` to the `this`
        }
        this.ancestors.forEach { label ->
            if (label != other) return@forEach
            queryCache[this to other] = 1
            return 1 // a path from the `this` to the `other`
        }
        return null // it means the two label is not comparable
    }

    override fun storeLattice(into: IStorage) {
        val curMap = mutableMapOf<String, IValue>()
        allLabels.forEach { curLabel ->
            if (curLabel == Label.INFIMUM || curLabel == Label.SUPREMUM) return@forEach
            curMap[curLabel.core] = curLabel.parents.mapValues { (_, vs) -> vs.core.strVal }.mapVal
        }
        val preMap = into.getMeta("__lattice__") as? MapVal
        val newMap = preMap?.core.orEmpty() + curMap
        into.setMeta("__lattice__" to newMap.mapVal)
        val preChanges = into.getMeta("__changes__") as? MapVal
        val curChanges = changeRecorder.map { (l, cs) -> l.core to cs.map { it.serialize }.listVal }
        val newChanges = preChanges?.core.orEmpty() + curChanges
        into.setMeta("__changes__" to newChanges.mapVal)
    }

    override fun loadLattice(from: IStorage) {
        val loadLattice = from.getMeta("__lattice__") as? MapVal ?: return
        loadLattice.forEach { (labelName, parentsName) ->
            val parentMap = parentsName as MapVal // cast it to the MapVal<Str, IValue>
            val parents = parentMap.mapValues { (_, prev) -> Label(prev as StrVal) }
            Label(labelName).parents += parents // records parents to the label in lattice
        }
        val loadRecords = from.getMeta("__changes__") as? MapVal ?: return
        loadRecords.core.forEach { (labelCore, changesCore) ->
            val loadLabel = Label(core = labelCore)
            val curChanges = changeRecorder[loadLabel].orEmpty()
            val loadChanges = (changesCore as ListVal).map { EdgeID(it as ListVal) }
            changeRecorder[loadLabel] = curChanges + loadChanges
        }
    }
}
