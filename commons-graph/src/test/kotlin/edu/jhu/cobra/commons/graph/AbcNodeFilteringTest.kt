package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

/*
 * Black-box tests for AbcNode reserved-property filtering: PROP_NODE_ID and PROP_OWNERS.
 *
 * - `get filters PROP_NODE_ID` -- verifies internal property hidden from user
 * - `set rejects PROP_NODE_ID` -- verifies require guard on reserved property
 * - `contains filters PROP_NODE_ID` -- verifies internal property excluded
 * - `asMap filters PROP_NODE_ID` -- verifies internal property excluded from map
 * - `update rejects PROP_NODE_ID` -- verifies require guard on bulk update
 * - `get filters PROP_OWNERS` -- verifies ownership mark hidden from user
 * - `set rejects PROP_OWNERS` -- verifies require guard on reserved property
 * - `contains filters PROP_OWNERS` -- verifies ownership mark excluded
 * - `asMap filters PROP_OWNERS` -- verifies ownership mark excluded from map
 * - `update rejects PROP_OWNERS` -- verifies require guard on bulk update
 */
internal class AbcNodeFilteringTest {
    private lateinit var storage: NativeStorageImpl

    private lateinit var node: TestNode

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        val sid = storage.addNode()
        node = TestNode()
        node.bind(storage, sid, NODE_ID_1)
    }

    // region PROP_NODE_ID filtering

    @Test
    fun `get filters PROP_NODE_ID`() {
        assertNull(node[AbcMultipleGraph.PROP_NODE_ID])
    }

    @Test
    fun `set rejects PROP_NODE_ID`() {
        assertFailsWith<IllegalArgumentException> {
            node[AbcMultipleGraph.PROP_NODE_ID] = "bad".strVal
        }
    }

    @Test
    fun `contains filters PROP_NODE_ID`() {
        assertFalse(AbcMultipleGraph.PROP_NODE_ID in node)
    }

    @Test
    fun `asMap filters PROP_NODE_ID`() {
        val map = node.asMap()

        assertFalse(map.containsKey(AbcMultipleGraph.PROP_NODE_ID))
    }

    @Test
    fun `update rejects PROP_NODE_ID`() {
        assertFailsWith<IllegalArgumentException> {
            node.update(mapOf(AbcMultipleGraph.PROP_NODE_ID to "bad".strVal))
        }
    }

    // endregion

    // region PROP_OWNERS filtering

    @Test
    fun `get filters PROP_OWNERS`() {
        storage.setNodeProperties(node.storageId, mapOf(AbcMultipleGraph.PROP_OWNERS to "g".strVal))

        assertNull(node[AbcMultipleGraph.PROP_OWNERS])
    }

    @Test
    fun `set rejects PROP_OWNERS`() {
        assertFailsWith<IllegalArgumentException> {
            node[AbcMultipleGraph.PROP_OWNERS] = "bad".strVal
        }
    }

    @Test
    fun `contains filters PROP_OWNERS`() {
        storage.setNodeProperties(node.storageId, mapOf(AbcMultipleGraph.PROP_OWNERS to "g".strVal))

        assertFalse(AbcMultipleGraph.PROP_OWNERS in node)
    }

    @Test
    fun `asMap filters PROP_OWNERS`() {
        storage.setNodeProperties(node.storageId, mapOf(AbcMultipleGraph.PROP_OWNERS to "g".strVal))

        assertFalse(node.asMap().containsKey(AbcMultipleGraph.PROP_OWNERS))
    }

    @Test
    fun `update rejects PROP_OWNERS`() {
        assertFailsWith<IllegalArgumentException> {
            node.update(mapOf(AbcMultipleGraph.PROP_OWNERS to "bad".strVal))
        }
    }

    // endregion

    // region Utility: assertFailsWith (inline for kotlin.test)

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName} but no exception was thrown")
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
    }

    // endregion
}
