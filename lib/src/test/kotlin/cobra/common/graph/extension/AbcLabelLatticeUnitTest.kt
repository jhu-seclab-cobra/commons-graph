package cobra.common.graph.extension

import cobra.common.graph.extension.label.DefaultLatticeImpl
import cobra.common.graph.extension.label.ILabelLattice
import cobra.common.graph.extension.label.JgraphtLatticeImpl
import cobra.common.graph.extension.label.Label
import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.toNid
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorage
import edu.jhu.cobra.commons.graph.storage.getMeta
import edu.jhu.cobra.commons.graph.storage.setMeta
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.MapVal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test

abstract class AbcLabelLatticeUnitTest {

    private lateinit var lattice: ILabelLattice
    private lateinit var edge: AbcEdge
    private lateinit var storage: IStorage

    abstract fun createLattice(): ILabelLattice

    class TestEdge(override val id: EdgeID, storage: IStorage) : AbcEdge(storage) {
        override val type get() = TODO("Not yet implemented")
    }

    @BeforeEach
    fun setUp() {
        lattice = createLattice()
        storage = NativeStorage() // 假设已有实现
        val srcID = edu.jhu.cobra.commons.graph.entity.toNid.also(storage::addNode)
        val dstID = edu.jhu.cobra.commons.graph.entity.toNid.also(storage::addNode)
        val edgeID = EdgeID(srcNid = srcID, dstNid = dstID, eType = "testEdge")
        edge = TestEdge(id = edgeID.also(storage::addEdge), storage = storage)
    }

    /** Tests for compareTo function **/

    @Test
    fun compareToShouldReturnCorrectOrderForHierarchy() = with(lattice) {
        val labelL1 = Label("L1")
        val labelL2 = Label("L2")
        val labelL3 = Label("L3")
        labelL1.parents = mapOf("parent" to labelL2)
        labelL2.parents = mapOf("parent" to labelL3)
        assertEquals(1, labelL1.compareTo(labelL2), "L1 should be greater than L2")
        assertEquals(1, labelL2.compareTo(labelL3), "L2 should be greater than L3")
        assertEquals(-1, labelL2.compareTo(labelL1), "L2 should be less than L1")
        assertEquals(-1, labelL3.compareTo(labelL2), "L3 should be less than L2")
    }

    @Test
    fun compareToShouldReturnNullForIncomparableLabels() = with(lattice) {
        val labelL1 = Label("L1")
        val labelL3 = Label("L3")
        val cmpResult = labelL1.compareTo(labelL3)
        assertNull(cmpResult, "L1 and L3 should be incomparable directly")
    }

    @Test
    fun compareToShouldHandleSupremumAndInfimum() = with(lattice) {
        val supremum = Label.SUPREMUM
        val infimum = Label.INFIMUM
        val labelL1 = Label("L1")
        assertEquals(1, supremum.compareTo(labelL1), "SUPREMUM should be greater than any other label")
        assertEquals(-1, infimum.compareTo(labelL1), "INFIMUM should be less than any other label")
        assertEquals(0, supremum.compareTo(supremum), "SUPREMUM should be equal to itself")
        assertEquals(0, infimum.compareTo(infimum), "INFIMUM should be equal to itself")
    }

    /** Tests for parents and ancestors properties **/

    @Test
    fun labelShouldReturnCorrectParents() = with(lattice) {
        val labelL1 = Label("L1")
        val labelL2 = Label("L2")

        labelL1.parents = mapOf("parent" to labelL2)
        assertEquals(labelL2, labelL1.parents["parent"], "L1 should have L2 as its parent")

    }

    @Test
    fun labelShouldReturnCorrectAncestorsSequence() = with(lattice) {
        val labelL1 = Label("L1")
        val labelL2 = Label("L2")
        val labelL3 = Label("L3")

        labelL1.parents = mapOf("parent" to labelL2)
        labelL2.parents = mapOf("parent" to labelL3)
        val expected = setOf(labelL2, labelL3)
        val ancestors = labelL1.ancestors.toSet()
        assertEquals(expected, ancestors, "Ancestors sequence should include L1, L2, and L3")

    }

    /** Tests for AbcEdge labels and change recording **/

    @Test
    fun labelsShouldBeEmptyInitially() = with(lattice) {
        assertTrue(edge.labels.isEmpty(), "Initial labels should be empty")
    }


    @Test
    fun labelsShouldUpdateChangeRecorderOnAddingLabels() = with(lattice) {
        val (label1, label2) = Label("L1") to Label("L2")
        edge.labels = setOf(label1, label2)
        assertEquals(setOf(label1, label2), edge.labels, "Labels should be updated correctly")
        assertEquals(setOf(edge.id), label1.changes, "Edge ID should be recorded in changeRecorder for L1")
        assertEquals(setOf(edge.id), label2.changes, "Edge ID should be recorded in changeRecorder for L2")
    }


    @Test
    fun labelsShouldUpdateChangeRecorderOnReplacingLabels() = with(lattice) {
        val (label1, label2) = Label("L1") to Label("L2")
        edge.labels = setOf(label1)
        edge.labels = setOf(label2)
        assertEquals(setOf(label2), edge.labels, "Labels should be updated correctly")
        assertEquals(setOf(edge.id), label2.changes, "Edge ID should be recorded in changeRecorder for L2")
    }

    @Test
    fun labelsShouldClearChangeRecorderOnRemovingAllLabels() = with(lattice) {
        val (label1, label2) = Label("L1") to Label("L2")
        edge.labels = setOf(label1, label2)
        edge.labels = emptySet()
        assertTrue(edge.labels.isEmpty(), "All labels should be removed")
        assertTrue(label1.changes.isEmpty(), "Change recorder for L1 should be empty")
        assertTrue(label2.changes.isEmpty(), "Change recorder for L2 should be empty")
    }

    /** Tests for storeLattice function **/

    @Test
    fun storeLatticeShouldStoreAnEmptyLattice() = with(lattice) {
        lattice.storeLattice(storage)
        val storedLattice = storage.getMeta("__lattice__") as? MapVal
        assertNotNull(storedLattice, "Stored lattice should not be null")
        assertTrue(storedLattice!!.core.isEmpty(), "Stored lattice should be empty")
    }

    /** Tests for loadLattice function **/

    @Test
    fun loadLatticeShouldLoadConsistentData() = with(lattice) {
        val (label1, label2) = Label("L1") to Label("L2")
        val allLabels = setOf(label1, label2, Label.SUPREMUM, Label.INFIMUM)
        edge.labels = setOf(label1, label2)
        label1.parents = emptyMap()
        label2.parents = emptyMap()
        lattice.storeLattice(storage)
        lattice.loadLattice(storage)
        assertEquals(allLabels, lattice.allLabels, "Labels should match after loading")
        assertEquals(setOf(edge.id), label1.changes, "ChangeRecorder for L1 should match after loading")
    }

    @Test
    fun loadLatticeShouldNotCrashOnInvalidDataStructure() = with(lattice) {
        storage.setMeta("__lattice__" to ListVal(emptyList())) // Invalid structure
        lattice.loadLattice(storage)
        assertTrue(lattice.allLabels.isNotEmpty(), "Invalid data should not clear existing labels")
    }

    @Test
    fun loadLatticeShouldRetainExistingLatticeIfDataIsMissing() = with(lattice) {
        lattice.loadLattice(storage)
        assertTrue(lattice.allLabels.isNotEmpty(), "Missing data should not affect current lattice")
    }
}

class JgraphtLatticeUnitTest : AbcLabelLatticeUnitTest() {
    override fun createLattice(): ILabelLattice = JgraphtLatticeImpl()
}

class DefaultLatticeUnitTest : AbcLabelLatticeUnitTest() {
    override fun createLattice(): ILabelLattice = DefaultLatticeImpl()
}
