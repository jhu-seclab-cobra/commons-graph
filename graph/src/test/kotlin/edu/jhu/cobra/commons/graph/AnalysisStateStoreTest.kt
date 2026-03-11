package edu.jhu.cobra.commons.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AnalysisStateStoreTest {
    private fun createStore(size: Int): AnalysisStateStore<Int> {
        val nodeIds = (0 until size).map { NodeID("n$it") }
        val indexMap = nodeIds.withIndex().associate { (i, nid) -> nid to i }
        return AnalysisStateStore(size) { nid -> indexMap[nid]!! }
    }

    // region Construction

    @Test
    fun `test constructor_zeroSize_succeeds`() {
        val store = AnalysisStateStore<String>(0) { 0 }

        assertEquals(0, store.size)
    }

    @Test
    fun `test constructor_negativeSize_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisStateStore<String>(-1) { 0 }
        }
    }

    // endregion

    // region Index-based access

    @Test
    fun `test get_unsetIndex_returnsNull`() {
        val store = createStore(10)

        assertNull(store[0])
        assertNull(store[9])
    }

    @Test
    fun `test set_and_get_roundTrips`() {
        val store = createStore(10)

        store[3] = 42

        assertEquals(42, store[3])
    }

    @Test
    fun `test set_overwrite_returnsNewValue`() {
        val store = createStore(10)
        store[0] = 1

        store[0] = 2

        assertEquals(2, store[0])
    }

    @Test
    fun `test set_null_clearsSlot`() {
        val store = createStore(10)
        store[5] = 99

        store[5] = null

        assertNull(store[5])
    }

    @Test
    fun `test get_outOfBounds_throwsIndexOutOfBounds`() {
        val store = createStore(5)

        assertFailsWith<IndexOutOfBoundsException> { store[5] }
        assertFailsWith<IndexOutOfBoundsException> { store[-1] }
    }

    @Test
    fun `test set_outOfBounds_throwsIndexOutOfBounds`() {
        val store = createStore(5)

        assertFailsWith<IndexOutOfBoundsException> { store[5] = 1 }
    }

    // endregion

    // region NodeID-based access

    @Test
    fun `test getByNode_unset_returnsNull`() {
        val store = createStore(10)

        assertNull(store.getByNode(NodeID("n0")))
    }

    @Test
    fun `test setByNode_and_getByNode_roundTrips`() {
        val store = createStore(10)
        val nid = NodeID("n7")

        store.setByNode(nid, 100)

        assertEquals(100, store.getByNode(nid))
    }

    @Test
    fun `test setByNode_and_indexGet_consistent`() {
        val store = createStore(10)

        store.setByNode(NodeID("n3"), 55)

        assertEquals(55, store[3])
    }

    @Test
    fun `test indexSet_and_getByNode_consistent`() {
        val store = createStore(10)

        store[4] = 77

        assertEquals(77, store.getByNode(NodeID("n4")))
    }

    // endregion

    // region Clear

    @Test
    fun `test clear_resetsAllSlots`() {
        val store = createStore(5)
        for (i in 0 until 5) store[i] = i * 10

        store.clear()

        for (i in 0 until 5) assertNull(store[i])
    }

    // endregion

    // region Generic type safety

    @Test
    fun `test stringState_roundTrips`() {
        val store = AnalysisStateStore<String>(3) { it.name.removePrefix("n").toInt() }

        store.setByNode(NodeID("n1"), "reaching_def")

        assertEquals("reaching_def", store.getByNode(NodeID("n1")))
    }

    @Test
    fun `test dataClassState_roundTrips`() {
        data class AbstractState(
            val value: Set<String>,
            val isBottom: Boolean,
        )

        val store = AnalysisStateStore<AbstractState>(5) { it.name.removePrefix("n").toInt() }
        val state = AbstractState(setOf("x", "y"), isBottom = false)

        store[2] = state

        assertEquals(state, store[2])
    }

    // endregion
}
