package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Tests for ColumnarProperties: columnar storage internals and ColumnViewMap.
 *
 * - `ColumnViewMap isEmpty returns true when entity has no properties` -- empty ColumnViewMap
 * - `ColumnViewMap containsKey returns true for existing and false for absent` -- containsKey paths
 * - `ColumnViewMap get returns value for existing and null for absent` -- get hit and miss
 * - `setColumnarProperties creates new column for first property of that name` -- column creation
 * - `removeEntityFromColumns removes column when it becomes empty` -- column cleanup
 * - `removeEntityFromColumns keeps column when other entities remain` -- column retention
 * - `deleteNode with no incident edges succeeds` -- empty adjacency sets
 */
internal class ColumnarPropertiesTest {
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
    }

    // region ColumnViewMap

    @Test
    fun `ColumnViewMap isEmpty returns true when entity has no properties`() {
        val nodeId = storage.addNode()

        val props = storage.getNodeProperties(nodeId)

        assertTrue(props.isEmpty())
    }

    @Test
    fun `ColumnViewMap containsKey returns true for existing and false for absent`() {
        val nodeId = storage.addNode(mapOf("name" to "Alice".strVal))

        val props = storage.getNodeProperties(nodeId)

        assertTrue(props.containsKey("name"))
        assertFalse(props.containsKey("absent"))
    }

    @Test
    fun `ColumnViewMap get returns value for existing and null for absent`() {
        val nodeId = storage.addNode(mapOf("name" to "Alice".strVal))

        val props = storage.getNodeProperties(nodeId)

        assertEquals("Alice", (props["name"] as StrVal).core)
        assertNull(props["absent"])
    }

    // endregion

    // region Columnar storage internals

    @Test
    fun `setColumnarProperties creates new column for first property of that name`() {
        val n1 = storage.addNode(mapOf("a" to "1".strVal))

        // "b" column does not exist yet; setNodeProperties creates it
        storage.setNodeProperties(n1, mapOf("b" to "2".strVal))

        assertEquals("1", (storage.getNodeProperty(n1, "a") as StrVal).core)
        assertEquals("2", (storage.getNodeProperty(n1, "b") as StrVal).core)
    }

    @Test
    fun `removeEntityFromColumns removes column when it becomes empty`() {
        val n1 = storage.addNode(mapOf("unique" to "only".strVal))

        storage.deleteNode(n1)

        // Add a new node — "unique" column should be gone, so no stale data
        val n2 = storage.addNode()
        assertNull(storage.getNodeProperty(n2, "unique"))
        assertTrue(storage.getNodeProperties(n2).isEmpty())
    }

    @Test
    fun `removeEntityFromColumns keeps column when other entities remain`() {
        val n1 = storage.addNode(mapOf("shared" to "A".strVal))
        val n2 = storage.addNode(mapOf("shared" to "B".strVal))

        storage.deleteNode(n1)

        assertEquals("B", (storage.getNodeProperty(n2, "shared") as StrVal).core)
    }

    @Test
    fun `deleteNode with no incident edges succeeds`() {
        val n1 = storage.addNode(mapOf("name" to "isolated".strVal))

        storage.deleteNode(n1)

        assertFalse(storage.containsNode(n1))
        assertTrue(storage.nodeIDs.isEmpty())
    }

    // endregion
}
