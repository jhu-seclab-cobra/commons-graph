package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.utils.EntityPropertyMap
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mapdb.DB
import org.mapdb.DBMaker
import kotlin.reflect.KClass
import kotlin.test.*

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
     * Test entityType field initialization and type checking
     */
    @Test
    fun testEntityTypeInitialization() {
        // EntityType should initially be null
        val entityTypeField = entityPropertyMap.javaClass.getDeclaredField("entityType")
        entityTypeField.isAccessible = true
        assertNull(entityTypeField.get(entityPropertyMap), "entityType should initially be null")

        // After adding an entity, entityType should be set to the entity's type
        val entity1 = NodeID("entity1")
        entityPropertyMap.put(entity1, mapOf("key" to "value".strVal))

        val entityType = entityTypeField.get(entityPropertyMap) as KClass<*>
        assertNotNull(entityType, "entityType should be set after adding an entity")
        assertEquals(NodeID::class, entityType, "entityType should be set to NodeID::class.java")
    }

    /**
     * Test unsupported operations
     */
    @Test
    fun testUnsupportedOperations() {
        // Test unsupported operations on keys set
        try {
            entityPropertyMap.keys.add(NodeID("newEntity"))
            fail("Should throw UnsupportedOperationException for keys.add")
        } catch (e: UnsupportedOperationException) {
            // Expected exception
        }

        try {
            entityPropertyMap.keys.addAll(listOf(NodeID("newEntity")))
            fail("Should throw UnsupportedOperationException for keys.addAll")
        } catch (e: UnsupportedOperationException) {
            // Expected exception
        }

        // Test unsupported operations on values collection
        try {
            entityPropertyMap.values.add(mapOf("key" to "value".strVal))
            fail("Should throw UnsupportedOperationException for values.add")
        } catch (e: UnsupportedOperationException) {
            // Expected exception
        }

        try {
            entityPropertyMap.values.addAll(listOf(mapOf("key" to "value".strVal)))
            fail("Should throw UnsupportedOperationException for values.addAll")
        } catch (e: UnsupportedOperationException) {
            // Expected exception
        }

        // Test unsupported operations on PropertyMap keys and values
        val entity = NodeID("entity")
        entityPropertyMap.put(entity, mapOf("key" to "value".strVal))
        val propertyMap = entityPropertyMap[entity] as MutableMap<String, IValue>

        try {
            propertyMap.keys.add("newKey")
            fail("Should throw UnsupportedOperationException for propertyMap.keys.add")
        } catch (e: UnsupportedOperationException) {
            // Expected exception
        }

        try {
            propertyMap.values.add("newValue".strVal)
            fail("Should throw UnsupportedOperationException for propertyMap.values.add")
        } catch (e: UnsupportedOperationException) {
            // Expected exception
        }
    }

    /**
     * Test exception handling for operating on closed database
     */
    @Test
    fun testExceptionHandlingForClosedDatabase() {
        // Setup entity
        val entity = NodeID("entity")
        entityPropertyMap.put(entity, mapOf("key" to "value".strVal))

        // Close database
        dbManager.close()

        // Test various operations that should throw exceptions
        val operationsToTest = listOf<() -> Unit>(
            { entityPropertyMap.put(entity, mapOf("key2" to "value2".strVal)) },
            { entityPropertyMap.remove(entity) },
            { entityPropertyMap.clear() },
            { entityPropertyMap.containsKey(entity) },
            { entityPropertyMap.size },
            { entityPropertyMap.isEmpty() },
            { entityPropertyMap[entity] },
            { entityPropertyMap.entries.size },
            { entityPropertyMap.keys.size },
            { entityPropertyMap.values.size }
        )

        var exceptionCount = 0
        for (operation in operationsToTest) {
            try {
                operation()
            } catch (e: IllegalAccessError) {
                // Any exception is acceptable, most likely IllegalAccessError
                exceptionCount++
            }
        }

        assertTrue(exceptionCount > 0, "At least some operations should throw exceptions on closed database")

        // Reset database for other tests
        setUp()
    }

    /**
     * Test consistency of internal structures
     */
    @Test
    fun testInternalStructureConsistency() {
        // Add and modify entities
        val entity1 = NodeID("entity1")
        val entity2 = NodeID("entity2")

        // Initial properties
        val props1 = mapOf("key1" to "value1".strVal, "key2" to "value2".strVal)
        val props2 = mapOf("keyA" to "valueA".strVal, "keyB" to "valueB".strVal)

        entityPropertyMap.put(entity1, props1)
        entityPropertyMap.put(entity2, props2)

        // Access internal fields to verify consistency
        val identitiesField = entityPropertyMap.javaClass.getDeclaredField("identities")
        identitiesField.isAccessible = true
        val identities = identitiesField.get(entityPropertyMap) as Map<*, *>

        val propertiesMapField = entityPropertyMap.javaClass.getDeclaredField("propertiesMap")
        propertiesMapField.isAccessible = true
        val propertiesMap = propertiesMapField.get(entityPropertyMap) as Map<*, *>

        // Verify identities contains both entities
        assertTrue(identities.containsKey(entity1.name), "identities should contain entity1")
        assertTrue(identities.containsKey(entity2.name), "identities should contain entity2")

        // Verify property keys are stored correctly
        val entity1Keys = identities[entity1.name] as SetVal
        val entity2Keys = identities[entity2.name] as SetVal

        assertEquals(props1.size, entity1Keys.size, "entity1 should have correct number of keys")
        assertEquals(props2.size, entity2Keys.size, "entity2 should have correct number of keys")

        // Verify all property values are stored with correct keys
        props1.forEach { (key, value) ->
            val propertiesKey = "${entity1.name}:$key"
            assertEquals(
                value, propertiesMap[propertiesKey],
                "propertiesMap should contain correct value for entity1:$key"
            )
        }

        props2.forEach { (key, value) ->
            val propertiesKey = "${entity2.name}:$key"
            assertEquals(
                value, propertiesMap[propertiesKey],
                "propertiesMap should contain correct value for entity2:$key"
            )
        }

        // Modify properties and verify consistency is maintained
        val propertyMap = entityPropertyMap[entity1] as MutableMap<String, IValue>
        propertyMap.remove("key1")
        propertyMap.put("key3", "value3".strVal)

        // Verify identities is updated
        val updatedEntity1Keys = identities[entity1.name] as SetVal
        assertTrue(
            !updatedEntity1Keys.core.map { it.core.toString() }.contains("key1"),
            "identities should not contain removed key"
        )
        assertTrue(
            updatedEntity1Keys.core.map { it.core.toString() }.contains("key3"),
            "identities should contain added key"
        )

        // Verify propertiesMap is updated
        assertNull(propertiesMap["${entity1.name}:key1"], "propertiesMap should not contain removed property")
        assertEquals(
            "value3".strVal, propertiesMap["${entity1.name}:key3"],
            "propertiesMap should contain added property"
        )
    }

    /**
     * Test property map collection views operations
     */
    @Test
    fun testPropertyMapCollectionViews() {
        val entity1 = NodeID("entity1")
        val initialProps = mapOf(
            "key1" to "value1".strVal,
            "key2" to "value2".strVal,
            "key3" to "value3".strVal
        )

        // Add entity with properties
        entityPropertyMap.put(entity1, initialProps)

        // Get the property map for the entity
        val propertyMap = entityPropertyMap[entity1] as MutableMap<String, IValue>

        // Test entries view
        val entries = propertyMap.entries
        assertEquals(3, entries.size, "Entries view should have correct size")

        // Test contains in entries
        val keyEntry = entries.find { it.key == "key1" }
        assertNotNull(keyEntry, "Should find entry with key1")
        assertEquals("value1".strVal, keyEntry.value, "Entry value should match")

        // Test entry removal
        assertTrue(entries.remove(keyEntry), "Should remove the entry")
        assertEquals(2, propertyMap.size, "Property map size should be updated")
        assertFalse(propertyMap.containsKey("key1"), "Property key should be removed")

        // Test keys view
        val keys = propertyMap.keys
        assertEquals(2, keys.size, "Keys view should have correct size")
        assertTrue(keys.contains("key2"), "Keys should contain key2")
        assertTrue(keys.contains("key3"), "Keys should contain key3")

        // Test key removal
        assertTrue(keys.remove("key2"), "Should remove key2")
        assertEquals(1, propertyMap.size, "Property map size should be updated")
        assertFalse(propertyMap.containsKey("key2"), "Property key should be removed")

        // Test values view
        val values = propertyMap.values
        assertEquals(1, values.size, "Values view should have correct size")
        assertTrue(values.contains("value3".strVal), "Values should contain value3")

        // Test value removal
        assertTrue(values.remove("value3".strVal), "Should remove value3")
        assertEquals(0, propertyMap.size, "Property map size should be updated")
        assertTrue(propertyMap.isEmpty(), "Property map should be empty")

        // Test bulk operations
        val newProps = mapOf(
            "newKey1" to "newValue1".strVal,
            "newKey2" to "newValue2".strVal
        )

        // Add properties and verify
        propertyMap.putAll(newProps)
        assertEquals(2, propertyMap.size, "Should have added new properties")
        assertEquals("newValue1".strVal, propertyMap["newKey1"], "New property value should be correct")

        // Test clear
        propertyMap.clear()
        assertTrue(propertyMap.isEmpty(), "Property map should be empty after clear")

        // Verify the entity still exists but has no properties
        assertTrue(entityPropertyMap.containsKey(entity1), "Entity should still exist")
        assertEquals(0, entityPropertyMap[entity1]?.size, "Entity should have no properties")
    }

    /**
     * Test comprehensive collection operations with both true and false cases
     */
    @Test
    fun testComprehensiveCollectionOperations() {
        // Setup test entities
        val entity1 = NodeID("entity1")
        val entity2 = NodeID("entity2")
        val entity3 = NodeID("entity3")

        val props1 = mapOf("name" to "Entity 1".strVal)
        val props2 = mapOf("name" to "Entity 2".strVal)
        val props3 = mapOf("name" to "Entity 3".strVal)

        entityPropertyMap.put(entity1, props1)
        entityPropertyMap.put(entity2, props2)

        // Test 1: Entity Map keys collection - containsAll true and false cases
        assertTrue(
            entityPropertyMap.keys.containsAll(listOf(entity1, entity2)),
            "keys.containsAll should return true for existing keys"
        )
        assertFalse(
            entityPropertyMap.keys.containsAll(listOf(entity1, entity3)),
            "keys.containsAll should return false when any key doesn't exist"
        )

        // Test 2: Entity Map keys collection - retainAll true and false cases
        val keysToRetain = listOf(entity1)
        assertTrue(
            entityPropertyMap.keys.retainAll(keysToRetain),
            "keys.retainAll should return true when changes occur"
        )
        assertEquals(1, entityPropertyMap.size, "After retainAll, only entity1 should remain")

        // Now retainAll should return false as no changes needed
        assertFalse(
            entityPropertyMap.keys.retainAll(keysToRetain),
            "keys.retainAll should return false when no changes occur"
        )

        // Re-add entity2 for further tests
        entityPropertyMap.put(entity2, props2)

        // Test 3: Entity Map keys collection - removeAll true and false cases
        val keysToRemove = listOf(entity2)
        assertTrue(
            entityPropertyMap.keys.removeAll(keysToRemove),
            "keys.removeAll should return true when changes occur"
        )
        assertEquals(1, entityPropertyMap.size, "After removeAll, only entity1 should remain")

        // Now removeAll should return false as entities already removed
        assertFalse(
            entityPropertyMap.keys.removeAll(keysToRemove),
            "keys.removeAll should return false when no changes occur"
        )

        // Test 4: Entity Map entries collection - add true and false cases
        val entry1 = object : MutableMap.MutableEntry<NodeID, Map<String, IValue>> {
            override val key = entity3
            override val value = props3
            override fun setValue(newValue: Map<String, IValue>): Map<String, IValue> = props3
        }

        assertTrue(
            entityPropertyMap.entries.add(entry1),
            "entries.add should return true for a new entry"
        )
        assertEquals(2, entityPropertyMap.size, "Size should be 2 after adding one entry")

        // Adding the same entry again should return false as no change occurs
        assertFalse(
            entityPropertyMap.entries.add(entry1),
            "entries.add should return false when entry already exists"
        )

        // Test 5: Entity Map values collection operations
        val valuesToRemove = listOf(props1)
        assertTrue(
            entityPropertyMap.values.removeAll(valuesToRemove),
            "values.removeAll should return true when changes occur"
        )
        assertEquals(1, entityPropertyMap.size, "Only one entity should remain after removing by value")

        // Now removeAll should return false as values already removed
        assertFalse(
            entityPropertyMap.values.removeAll(valuesToRemove),
            "values.removeAll should return false when no changes occur"
        )

        // Test 6: Property Map collection operations - true/false cases
        // Re-add entities for property map testing
        entityPropertyMap.clear()
        val initialProps = mapOf(
            "key1" to "value1".strVal,
            "key2" to "value2".strVal,
            "key3" to "value3".strVal
        )

        entityPropertyMap.put(entity1, initialProps)
        val propertyMap = entityPropertyMap[entity1] as MutableMap<String, IValue>

        // Test property map entries
        val propEntry = propertyMap.entries.find { it.key == "key1" }
        assertNotNull(propEntry, "Should find entry with key1")

        assertTrue(
            propertyMap.entries.remove(propEntry),
            "entries.remove should return true when entry exists"
        )
        assertFalse(
            propertyMap.entries.remove(propEntry),
            "entries.remove should return false when entry doesn't exist"
        )

        // Test property map keys
        assertTrue(
            propertyMap.keys.remove("key2"),
            "keys.remove should return true when key exists"
        )
        assertFalse(
            propertyMap.keys.remove("key2"),
            "keys.remove should return false when key doesn't exist"
        )

        // Test property map values
        assertTrue(
            propertyMap.values.remove("value3".strVal),
            "values.remove should return true when value exists"
        )
        assertFalse(
            propertyMap.values.remove("value3".strVal),
            "values.remove should return false when value doesn't exist"
        )

        // Test property map keys retainAll
        propertyMap.putAll(mapOf("keyA" to "valueA".strVal, "keyB" to "valueB".strVal))
        assertTrue(
            propertyMap.keys.retainAll(listOf("keyA")),
            "keys.retainAll should return true when changes occur"
        )
        assertEquals(1, propertyMap.size, "Size should be 1 after retainAll")

        assertFalse(
            propertyMap.keys.retainAll(listOf("keyA")),
            "keys.retainAll should return false when no changes occur"
        )

        // Test 7: containsValue - true/false cases
        propertyMap.clear()
        propertyMap.put("testKey", "testValue".strVal)

        assertTrue(
            propertyMap.containsValue("testValue".strVal),
            "containsValue should return true for existing value"
        )
        assertFalse(
            propertyMap.containsValue("nonExistingValue".strVal),
            "containsValue should return false for non-existing value"
        )
    }

    /**
     * Test concurrent modifications and boundary conditions with PropertyMap
     */
    @Test
    fun testConcurrentModifications() {
        // Setup: Create entities with properties
        val entity1 = NodeID("entity1")

        // Test case 1: Modifying a property map while iterating through it (properly)
        val initialProps = mapOf(
            "key1" to "value1".strVal,
            "key2" to "value2".strVal,
            "key3" to "value3".strVal
        )
        entityPropertyMap.put(entity1, initialProps)

        // Get the property map and use the iterator's remove
        val propertyMap = entityPropertyMap[entity1] as MutableMap<String, IValue>

        // Test iterator safety
        val entryIterator = propertyMap.entries.iterator()
        var count = 0

        while (entryIterator.hasNext()) {
            val entry = entryIterator.next()
            count++
            if (entry.key == "key2") {
                entryIterator.remove()
            }
        }

        assertEquals(3, count, "Should have iterated through all 3 entries")
        assertEquals(2, propertyMap.size, "Should have 2 entries left after removing one")
        assertFalse(propertyMap.containsKey("key2"), "key2 should be removed")

        // Test case 2: Using retainAll on an empty property map
        entityPropertyMap.put(entity1, emptyMap())
        val emptyPropertyMap = entityPropertyMap[entity1] as MutableMap<String, IValue>
        assertFalse(
            emptyPropertyMap.keys.retainAll(listOf("nonexistent")),
            "retainAll on empty map should return false"
        )

        // Test case 3: Handling of extreme property counts
        entityPropertyMap.clear()
        val largePropsCount = 100
        val largeProps = (1..largePropsCount).associate {
            "key$it" to "value$it".strVal
        }
        
        entityPropertyMap.put(entity1, largeProps)
        val largePropertyMap = entityPropertyMap[entity1] as MutableMap<String, IValue>

        assertEquals(
            largePropsCount, largePropertyMap.size,
            "Should handle a large number of properties"
        )

        // Remove half the properties using the entries collection
        val halfEntries = largePropertyMap.entries.take(largePropsCount / 2).toList()
        largePropertyMap.entries.removeAll(halfEntries)

        assertEquals(
            largePropsCount / 2, largePropertyMap.size,
            "Should have half the properties after removal"
        )
    }
}
