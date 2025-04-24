package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.utils.EntityPropertyMap
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mapdb.DB
import org.mapdb.DBMaker
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test the functionality of MapDB implementation of EntityPropertyMap
 */
class EntityPropertyMapTest {

    private lateinit var dbManager: DB
    private lateinit var entityPropertyMap: EntityPropertyMap<NodeID>

    @Before
    fun setUp() {
        // Create an in-memory database for testing
        dbManager = DBMaker.memoryDB().make()
        entityPropertyMap = EntityPropertyMap(dbManager, "test-entity-props")
    }

    @After
    fun tearDown() {
        dbManager.close()
    }

    /**
     * Test adding and retrieving entity properties
     */
    @Test
    fun testPutAndGet() {
        val entity1 = NodeID("entity1")
        val props1 = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal,
            "active" to true.boolVal
        )

        // Test put method - Store an entity and its properties
        val prevProps = entityPropertyMap.put(entity1, props1)
        assertNull(prevProps, "Should return null when adding an entity for the first time")

        // Test get method - Retrieve the property map of an entity
        val retrievedProps = entityPropertyMap.get(entity1)
        assertEquals(props1.size, retrievedProps?.size, "Should return the same number of properties")

        props1.forEach { (key, value) ->
            assertEquals(value, retrievedProps?.get(key), "Should return the correct property values")
        }
    }

    /**
     * Test updating entity properties
     */
    @Test
    fun testUpdateProperties() {
        val entity1 = NodeID("entity1")
        val initialProps = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal
        )

        // Initial property addition
        entityPropertyMap.put(entity1, initialProps)

        // Update properties
        val updatedProps = mapOf(
            "name" to "Updated Entity".strVal,
            "count" to 100.numVal,
            "newProp" to "new value".strVal
        )

        val prevProps = entityPropertyMap.put(entity1, updatedProps)
        // Check if the returned old properties are correct
        assertEquals(initialProps.size, prevProps?.size, "Should return the previous number of properties")
        initialProps.forEach { (key, value) ->
            assertEquals(value, prevProps?.get(key), "Should return the previous property values")
        }

        // Check updated properties
        val retrievedProps = entityPropertyMap.get(entity1)
        assertEquals(updatedProps.size, retrievedProps?.size, "Should update the number of properties")
        updatedProps.forEach { (key, value) ->
            assertEquals(value, retrievedProps?.get(key), "Should update property values")
        }
    }

    /**
     * Test entity removal
     */
    @Test
    fun testRemoveEntity() {
        val entity1 = NodeID("entity1")
        val props1 = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal
        )

        // Add then remove an entity
        entityPropertyMap.put(entity1, props1)
        val removedProps = entityPropertyMap.remove(entity1)

        // Check if the returned deleted properties are correct
        assertEquals(props1.size, removedProps?.size, "Should return the number of deleted properties")
        props1.forEach { (key, value) ->
            assertEquals(value, removedProps?.get(key), "Should return the values of deleted properties")
        }

        // Check if the entity is actually deleted
        assertNull(entityPropertyMap.get(entity1), "Entity should be deleted")
        assertFalse(entityPropertyMap.containsKey(entity1), "Entity should be deleted")
    }

    /**
     * Test collection views: entries, keys, values
     */
    @Test
    fun testCollectionViews() {
        val entity1 = NodeID("entity1")
        val entity2 = NodeID("entity2")

        val props1 = mapOf("name" to "Entity 1".strVal)
        val props2 = mapOf("name" to "Entity 2".strVal)

        entityPropertyMap.put(entity1, props1)
        entityPropertyMap.put(entity2, props2)

        // Test size
        assertEquals(2, entityPropertyMap.size, "Should have 2 entities")

        // Test keys view
        assertEquals(2, entityPropertyMap.keys.size, "Should have 2 keys")
        assertTrue(entityPropertyMap.keys.contains(entity1), "Should contain entity1")
        assertTrue(entityPropertyMap.keys.contains(entity2), "Should contain entity2")

        // Test entries view
        val entrySet = entityPropertyMap.entries
        assertEquals(2, entrySet.size, "Should have 2 entries")

        // Test values view
        val valueCol = entityPropertyMap.values
        assertEquals(2, valueCol.size, "Should have 2 values")
    }

    /**
     * Test bulk operations: putAll
     */
    @Test
    fun testBulkOperations() {
        val entity1 = NodeID("entity1")
        val entity2 = NodeID("entity2")

        val props1 = mapOf("name" to "Entity 1".strVal)
        val props2 = mapOf("name" to "Entity 2".strVal)

        val entitiesToAdd = mapOf(
            entity1 to props1,
            entity2 to props2
        )

        // Test bulk addition
        entityPropertyMap.putAll(entitiesToAdd)

        assertEquals(2, entityPropertyMap.size, "Should add 2 entities")
        assertEquals(props1["name"], entityPropertyMap[entity1]?.get("name"), "entity1 properties should be correct")
        assertEquals(props2["name"], entityPropertyMap[entity2]?.get("name"), "entity2 properties should be correct")
    }

    /**
     * Test PropertyMap inner class functionality
     */
    @Test
    fun testPropertyMapOperations() {
        val entity1 = NodeID("entity1")
        val initialProps = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal
        )

        // Add initial properties
        entityPropertyMap.put(entity1, initialProps)

        // Get PropertyMap instance
        val propertyMap = entityPropertyMap[entity1]

        // Check PropertyMap functionality
        assertFalse(propertyMap!!.isEmpty(), "PropertyMap should not be empty")
        assertEquals(2, propertyMap.size, "PropertyMap should have 2 properties")
        assertEquals(initialProps["name"], propertyMap["name"], "Should return correct name property")
        assertEquals(initialProps["count"], propertyMap["count"], "Should return correct count property")
        assertTrue(propertyMap.containsKey("name"), "Should contain name key")
    }

    /**
     * Test empty and null value handling
     */
    @Test
    fun testEmptyAndNullValues() {
        assertTrue(entityPropertyMap.isEmpty(), "Initial map should be empty")

        val entity1 = NodeID("entity1")
        assertNull(entityPropertyMap[entity1], "Should return null for non-added entity")
        assertFalse(entityPropertyMap.containsKey(entity1), "Should not contain non-added entity")

        // Test empty property set
        entityPropertyMap.put(entity1, emptyMap())
        assertTrue(entityPropertyMap.containsKey(entity1), "Should contain entity with empty properties")
        assertEquals(0, entityPropertyMap[entity1]?.size, "Should have 0 properties")
    }

    /**
     * Test clear method
     */
    @Test
    fun testClear() {
        // Add multiple entities
        val entity1 = NodeID("entity1")
        val entity2 = NodeID("entity2")

        entityPropertyMap.put(entity1, mapOf("key1" to "value1".strVal))
        entityPropertyMap.put(entity2, mapOf("key2" to "value2".strVal))

        assertEquals(2, entityPropertyMap.size, "Should have 2 entities after addition")

        // Test clear method
        entityPropertyMap.clear()

        assertEquals(0, entityPropertyMap.size, "Should be empty after clearing")
        assertTrue(entityPropertyMap.isEmpty(), "isEmpty should return true after clearing")
        assertFalse(entityPropertyMap.containsKey(entity1), "Should not contain entity1 after clearing")
        assertFalse(entityPropertyMap.containsKey(entity2), "Should not contain entity2 after clearing")
    }

    /**
     * Test containsValue method
     */
    @Test
    fun testContainsValue() {
        val entity1 = NodeID("entity1")
        val props1 = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal
        )

        // Add entity
        entityPropertyMap.put(entity1, props1)

        // Test containsValue - matching case
        val sameProps = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal
        )
        assertTrue(entityPropertyMap.containsValue(sameProps), "Should contain exactly the same property mapping")

        // Test containsValue - non-matching case
        val differentProps = mapOf(
            "name" to "Different Name".strVal,
            "count" to 42.numVal
        )
        assertFalse(entityPropertyMap.containsValue(differentProps), "Should not contain different property mapping")

        // Test partial match but different key sets
        val partialProps = mapOf(
            "name" to "Entity 1".strVal
        )
        assertFalse(
            entityPropertyMap.containsValue(partialProps),
            "Different key sets should not be considered contained"
        )
    }

    /**
     * Test more functionality of PropertyMap inner class
     */
    @Test
    fun testPropertyMapAdvanced() {
        val entity1 = NodeID("entity1")
        val initialProps = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal
        )

        // Add initial properties
        entityPropertyMap.put(entity1, initialProps)

        // Get PropertyMap and convert to MutableMap
        val propertyMap = entityPropertyMap[entity1] as? MutableMap<String, IValue>
        requireNotNull(propertyMap) { "PropertyMap should be of MutableMap type" }

        // Test put method
        val oldValue = propertyMap.put("count", 100.numVal)
        assertEquals(42.numVal, oldValue, "Should return old value")
        assertEquals(100.numVal, propertyMap["count"], "Should update value")

        // Test remove method
        val removedValue = propertyMap.remove("name")
        assertEquals("Entity 1".strVal, removedValue, "Should return removed value")
        assertNull(propertyMap["name"], "Should return null after removal")

        // Test putAll method
        propertyMap.putAll(
            mapOf(
                "newKey1" to "new value 1".strVal,
                "newKey2" to "new value 2".strVal
            )
        )
        assertEquals("new value 1".strVal, propertyMap["newKey1"], "Should add new value")
        assertEquals("new value 2".strVal, propertyMap["newKey2"], "Should add new value")

        // Test clear method
        propertyMap.clear()
        assertTrue(propertyMap.isEmpty(), "Should be empty after clearing")
        assertEquals(0, propertyMap.size, "Size should be 0 after clearing")
    }

    /**
     * Test exceptions and edge cases
     */
    @Test
    fun testEdgeCases() {
        // Test removing non-existent entity
        val nonExistentEntity = NodeID("nonexistent")
        val removedProps = entityPropertyMap.remove(nonExistentEntity)
        assertNull(removedProps, "Removing non-existent entity should return null")

        // Test empty property name
        val entity1 = NodeID("entity1")
        val propsWithEmptyKey = mapOf(
            "" to "Empty key".strVal,
            "normalKey" to "Normal value".strVal
        )
        entityPropertyMap.put(entity1, propsWithEmptyKey)
        val retrievedProps = entityPropertyMap[entity1]
        assertEquals("Empty key".strVal, retrievedProps?.get(""), "Should support empty property name")

        // Test special character property names
        val specialChars = mapOf(
            "!@#$%^&*()" to "Special chars".strVal,
            "你好世界" to "Unicode chars".strVal,
            "a:b" to "Colon in key".strVal  // This is a critical test as the implementation uses colon as a separator
        )
        entityPropertyMap.put(entity1, specialChars)
        val retrieved = entityPropertyMap[entity1]
        assertEquals(
            "Special chars".strVal,
            retrieved?.get("!@#$%^&*()"),
            "Should support special character property names"
        )
        assertEquals(
            "Unicode chars".strVal,
            retrieved?.get("你好世界"),
            "Should support Unicode character property names"
        )
        assertEquals("Colon in key".strVal, retrieved?.get("a:b"), "Should support property names containing colons")
    }

    /**
     * Test collection view modification operations
     */
    @Test
    fun testCollectionViewModifications() {
        val entity1 = NodeID("entity1")
        val entity2 = NodeID("entity2")
        val entity3 = NodeID("entity3")

        val props1 = mapOf("name" to "Entity 1".strVal)
        val props2 = mapOf("name" to "Entity 2".strVal)
        val props3 = mapOf("name" to "Entity 3".strVal)

        entityPropertyMap.putAll(
            mapOf(
                entity1 to props1,
                entity2 to props2,
                entity3 to props3
            )
        )

        // Test removal via keys
        assertTrue(entityPropertyMap.keys.remove(entity1), "Should successfully remove entity1")
        assertFalse(entityPropertyMap.containsKey(entity1), "Should not contain entity1 after removal")
        assertEquals(2, entityPropertyMap.size, "Should have 2 entities after removal")

        // Test removal via entries
        val entity2Entry = entityPropertyMap.entries.find { it.key == entity2 }
        requireNotNull(entity2Entry) { "Should be able to find entity2 entry" }
        assertTrue(entityPropertyMap.entries.remove(entity2Entry), "Should successfully remove entity2 entry")
        assertFalse(entityPropertyMap.containsKey(entity2), "Should not contain entity2 after removal")

        // Test removal via values
        val entity3Props = entityPropertyMap[entity3]
        requireNotNull(entity3Props) { "Should be able to get entity3 properties" }
        assertTrue(entityPropertyMap.values.remove(entity3Props), "Should successfully remove entity3 properties")
        assertFalse(entityPropertyMap.containsKey(entity3), "Should not contain entity3 after removal")

        // Should now be empty
        assertTrue(entityPropertyMap.isEmpty(), "Should be empty after removing all entities")
    }

    /**
     * Test large data set scenario
     */
    @Test
    fun testLargeDataSet() {
        val entityCount = 100
        val propsPerEntity = 50

        // Create and add a large number of entities
        for (i in 1..entityCount) {
            val entity = NodeID("entity$i")
            val props = (1..propsPerEntity).associate {
                "prop$it" to "value$it for entity$i".strVal
            }
            entityPropertyMap.put(entity, props)
        }

        // Verify size
        assertEquals(entityCount, entityPropertyMap.size, "Should add correct number of entities")

        // Verify some random entities
        for (i in listOf(1, 25, 50, 75, 100)) {
            if (i <= entityCount) {
                val entity = NodeID("entity$i")
                val props = entityPropertyMap[entity]
                requireNotNull(props) { "Should be able to get entity$i" }
                assertEquals(propsPerEntity, props.size, "entity$i should have correct number of properties")
                assertEquals(
                    "value1 for entity$i".strVal,
                    props["prop1"],
                    "Should be able to get correct property value"
                )
            }
        }

        // Test clearing
        entityPropertyMap.clear()
        assertEquals(0, entityPropertyMap.size, "Should be empty after clearing")
    }

    /**
     * Test null value handling
     */
    @Test
    fun testNullValueHandling() {
        val entity1 = NodeID("entity1")
        val propsWithNull = mapOf(
            "nullValue" to NullVal,
            "normalValue" to "normal".strVal
        )

        entityPropertyMap.put(entity1, propsWithNull)
        val retrievedProps = entityPropertyMap[entity1]

        assertEquals(NullVal, retrievedProps?.get("nullValue"), "Should handle NullVal correctly")
        assertEquals("normal".strVal, retrievedProps?.get("normalValue"), "Should handle normal values correctly")
    }

    /**
     * Test collection view operations on PropertyMap
     */
    @Test
    fun testPropertyMapCollectionViews() {
        val entity1 = NodeID("entity1")
        val initialProps = mapOf(
            "key1" to "value1".strVal,
            "key2" to "value2".strVal,
            "key3" to "value3".strVal
        )

        entityPropertyMap.put(entity1, initialProps)
        val propertyMap = entityPropertyMap[entity1] as? MutableMap<String, IValue>
        requireNotNull(propertyMap) { "PropertyMap should be of MutableMap type" }

        // Test keys collection
        val keys = propertyMap.keys
        assertEquals(3, keys.size, "Should have 3 keys")
        assertTrue(keys.contains("key1"), "Should contain key1")

        // Test values collection
        val values = propertyMap.values
        assertEquals(3, values.size, "Should have 3 values")
        assertTrue(values.contains("value1".strVal), "Should contain value1")

        // Test removal from keys
        assertTrue(keys.remove("key1"), "Should successfully remove key1")
        assertFalse(propertyMap.containsKey("key1"), "Should not contain key1 after removal")
        assertEquals(2, propertyMap.size, "Should have 2 properties after removal")

        // Test removal from values
        assertTrue(values.remove("value2".strVal), "Should successfully remove value2")
        assertFalse(propertyMap.containsValue("value2".strVal), "Should not contain value2 after removal")
        assertEquals(1, propertyMap.size, "Should have 1 property after removal")
    }

    /**
     * Test concurrent modification scenarios for PropertyMap
     */
    @Test
    fun testConcurrentModification() {
        val entity1 = NodeID("entity1")
        val initialProps = mapOf(
            "key1" to "value1".strVal,
            "key2" to "value2".strVal,
            "key3" to "value3".strVal
        )

        entityPropertyMap.put(entity1, initialProps)
        val propertyMap = entityPropertyMap[entity1] as? MutableMap<String, IValue>
        requireNotNull(propertyMap) { "PropertyMap should be of MutableMap type" }

        // Test modification during iteration
        var exception = false
        try {
            for (key in propertyMap.keys) {
                if (key == "key1") {
                    propertyMap.remove("key2")
                }
            }
        } catch (e: ConcurrentModificationException) {
            exception = true
        }

        // MapDB's implementation might not throw ConcurrentModificationException, so we check if the modification took effect
        assertFalse(propertyMap.containsKey("key2"), "key2 should be removed")
    }

    /**
     * Test large property values and special values
     */
    @Test
    fun testLargeAndSpecialValues() {
        val entity1 = NodeID("entity1")

        // Create a large string value
        val largeString = StringBuilder()
        for (i in 1..10000) {
            largeString.append("a")
        }

        // Test large property value
        val largeProps = mapOf(
            "largeString" to largeString.toString().strVal
        )

        entityPropertyMap.put(entity1, largeProps)
        val retrievedProps = entityPropertyMap[entity1]
        assertEquals(
            largeString.toString().strVal,
            retrievedProps?.get("largeString"),
            "Should handle large string values correctly"
        )

        // Test extreme numeric values
        val extremeValues = mapOf(
            "maxInt" to Int.MAX_VALUE.numVal,
            "minInt" to Int.MIN_VALUE.numVal,
            "maxLong" to Long.MAX_VALUE.numVal,
            "minLong" to Long.MIN_VALUE.numVal,
            "maxDouble" to Double.MAX_VALUE.numVal,
            "minDouble" to Double.MIN_VALUE.numVal,
            "posInfinity" to Double.POSITIVE_INFINITY.numVal,
            "negInfinity" to Double.NEGATIVE_INFINITY.numVal,
            "nan" to Double.NaN.numVal
        )

        entityPropertyMap.put(entity1, extremeValues)
        val retrieved = entityPropertyMap[entity1]

        assertEquals(Int.MAX_VALUE.numVal, retrieved?.get("maxInt"), "Should handle Int.MAX_VALUE correctly")
        assertEquals(Int.MIN_VALUE.numVal, retrieved?.get("minInt"), "Should handle Int.MIN_VALUE correctly")
        assertEquals(Long.MAX_VALUE.numVal, retrieved?.get("maxLong"), "Should handle Long.MAX_VALUE correctly")
        assertEquals(Long.MIN_VALUE.numVal, retrieved?.get("minLong"), "Should handle Long.MIN_VALUE correctly")
        assertEquals(Double.MAX_VALUE.numVal, retrieved?.get("maxDouble"), "Should handle Double.MAX_VALUE correctly")
        assertEquals(Double.MIN_VALUE.numVal, retrieved?.get("minDouble"), "Should handle Double.MIN_VALUE correctly")
        assertEquals(
            Double.POSITIVE_INFINITY.numVal,
            retrieved?.get("posInfinity"),
            "Should handle positive infinity correctly"
        )
        assertEquals(
            Double.NEGATIVE_INFINITY.numVal,
            retrieved?.get("negInfinity"),
            "Should handle negative infinity correctly"
        )
        assertEquals(Double.NaN.numVal, retrieved?.get("nan"), "Should handle NaN correctly")
    }

    /**
     * Test entity ID boundary conditions
     */
    @Test
    fun testEntityIDBoundaries() {
        // Test empty name entity ID
        val emptyNameEntity = NodeID("")
        val emptyProps = mapOf("key" to "value".strVal)

        entityPropertyMap.put(emptyNameEntity, emptyProps)
        val retrievedProps = entityPropertyMap[emptyNameEntity]
        assertEquals("value".strVal, retrievedProps?.get("key"), "Should handle empty name entity ID correctly")

        // Test entity ID with special characters
        val specialNameEntity = NodeID("entity:with:colons")
        entityPropertyMap.put(specialNameEntity, emptyProps)
        val specialRetrieved = entityPropertyMap[specialNameEntity]
        assertEquals("value".strVal, specialRetrieved?.get("key"), "Should handle entity ID with colons correctly")

        // Test long name entity ID
        val longName = StringBuilder()
        for (i in 1..1000) {
            longName.append("x")
        }
        val longNameEntity = NodeID(longName.toString())
        entityPropertyMap.put(longNameEntity, emptyProps)
        val longRetrieved = entityPropertyMap[longNameEntity]
        assertEquals("value".strVal, longRetrieved?.get("key"), "Should handle long name entity ID correctly")
    }

    /**
     * Test consistency of repeated operations
     */
    @Test
    fun testOperationConsistency() {
        val entity1 = NodeID("entity1")
        val props1 = mapOf("key" to "value".strVal)

        // Repeatedly add and remove the same entity
        for (i in 1..10) {
            entityPropertyMap.put(entity1, props1)
            assertEquals(
                "value".strVal,
                entityPropertyMap[entity1]?.get("key"),
                "Should retrieve correctly after add #${i}"
            )

            entityPropertyMap.remove(entity1)
            assertNull(entityPropertyMap[entity1], "Should return null after delete #${i}")
        }

        // Add and immediately update multiple times
        entityPropertyMap.put(entity1, props1)
        for (i in 1..10) {
            val newProps = mapOf("key" to "value${i}".strVal)
            entityPropertyMap.put(entity1, newProps)
            assertEquals(
                "value${i}".strVal,
                entityPropertyMap[entity1]?.get("key"),
                "Should retrieve correctly after update #${i}"
            )
        }
    }

    /**
     * Test behavior after database closure
     */
    @Test
    fun testAfterDatabaseClose() {
        val entity1 = NodeID("entity1")
        val props1 = mapOf("key" to "value".strVal)

        // Normal addition
        entityPropertyMap.put(entity1, props1)
        assertEquals("value".strVal, entityPropertyMap[entity1]?.get("key"), "Should add correctly")

        // Close database
        dbManager.close()

        // The following operations should throw an exception
        var exceptionThrown = false
        try {
            entityPropertyMap.get(entity1)
        } catch (e: IllegalAccessError) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown, "Should throw exception after database closure")

        // Reset the database to avoid affecting other tests
        setUp()
    }
}
