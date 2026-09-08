package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import org.mapdb.DB
import org.mapdb.DBMaker
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Tests for EntityPropertyMap: entity-level operations, edge cases, and bulk operations.
 *
 * - `basic put and get operations`
 * - `property update and override`
 * - `entity removal`
 * - `property map operations`
 * - `property map update applies delta without touching other keys`
 * - `property map update of absent key removal is a no-op`
 * - `property map collection views`
 * - `edge cases for property names and values`
 * - `null and empty value handling`
 * - `large data set handling`
 * - `bulk operations`
 * - `collection view operations`
 */
internal class EntityPropertyMapTest {
    private lateinit var dbManager: DB

    private lateinit var entityPropertyMap: EntityPropertyMap

    @BeforeTest
    fun setUp() {
        dbManager = DBMaker.memoryDB().make()
        entityPropertyMap = EntityPropertyMap(dbManager, "test-entity-props")
    }

    @AfterTest
    fun tearDown() {
        dbManager.close()
    }

    // -- Basic operations --

    @Test
    fun `basic put and get operations`() {
        val entity = 1
        val props =
            mapOf(
                "name" to "Entity 1".strVal,
                "count" to 42.intVal,
                "active" to true.boolVal,
            )
        assertNull(entityPropertyMap.put(entity, props))
        assertEquals<Map<String, IValue>>(props, entityPropertyMap[entity]!!)
    }

    @Test
    fun `property update and override`() {
        val entity = 1
        val initial = mapOf("name" to "Initial".strVal)
        val updated = mapOf("name" to "Updated".strVal, "newProp" to "new value".strVal)

        entityPropertyMap.put(entity, initial)
        assertEquals<Map<String, IValue>>(initial, entityPropertyMap.put(entity, updated)!!)
        assertEquals<Map<String, IValue>>(updated, entityPropertyMap[entity]!!)
    }

    @Test
    fun `entity removal`() {
        val entity = 1
        val props = mapOf("name" to "Entity 1".strVal)
        entityPropertyMap.put(entity, props)
        assertEquals(props, entityPropertyMap.remove(entity))
        assertNull(entityPropertyMap[entity])
        assertFalse(entityPropertyMap.containsKey(entity))
    }

    // -- Property map operations --

    @Test
    fun `property map operations`() {
        val entity = 1
        val initial = mapOf("key1" to "value1".strVal, "key2" to "value2".strVal)
        entityPropertyMap.put(entity, initial)
        val propertyMap = entityPropertyMap[entity]!!

        propertyMap["key3"] = "value3".strVal
        assertEquals("value3".strVal, propertyMap["key3"])

        propertyMap["key1"] = "updated".strVal
        assertEquals("updated".strVal, propertyMap["key1"])

        propertyMap.remove("key2")
        assertFalse(propertyMap.containsKey("key2"))
    }

    @Test
    fun `property map update applies delta without touching other keys`() {
        val entity = 1
        entityPropertyMap.put(entity, mapOf("a" to 1.intVal, "b" to 2.intVal, "c" to 3.intVal))
        val propertyMap = entityPropertyMap[entity]!!

        propertyMap.update(mapOf("b" to 20.intVal, "c" to null, "d" to 4.intVal))

        assertEquals(1.intVal, propertyMap["a"])
        assertEquals(20.intVal, propertyMap["b"])
        assertFalse(propertyMap.containsKey("c"))
        assertEquals(4.intVal, propertyMap["d"])
        assertEquals(3, propertyMap.size)
    }

    @Test
    fun `property map update of absent key removal is a no-op`() {
        val entity = 1
        entityPropertyMap.put(entity, mapOf("a" to 1.intVal))
        val propertyMap = entityPropertyMap[entity]!!

        propertyMap.update(mapOf("missing" to null))

        assertEquals(mapOf<String, IValue>("a" to 1.intVal), propertyMap.toMap())
    }

    @Test
    fun `property map collection views`() {
        val entity = 1
        val props = mapOf("key1" to "value1".strVal, "key2" to "value2".strVal, "key3" to "value3".strVal)
        entityPropertyMap.put(entity, props)
        val propertyMap = entityPropertyMap[entity]!!

        assertEquals(3, propertyMap.entries.size)
        assertTrue(propertyMap.entries.any { it.key == "key1" && it.value == "value1".strVal })

        assertEquals(3, propertyMap.keys.size)
        assertTrue(propertyMap.keys.containsAll(listOf("key1", "key2", "key3")))

        assertEquals(3, propertyMap.values.size)
        assertTrue(propertyMap.values.containsAll(listOf("value1".strVal, "value2".strVal, "value3".strVal)))
    }

    // -- Edge cases --

    @Test
    fun `edge cases for property names and values`() {
        val entity = 1
        val edgeCases =
            mapOf(
                "" to "Empty key".strVal,
                "!@#\$%^&*()" to "Special chars".strVal,
                "a:b" to "Colon in key".strVal,
                "key with spaces" to "Spaces in key".strVal,
            )
        entityPropertyMap.put(entity, edgeCases)
        val retrieved = entityPropertyMap[entity]
        edgeCases.forEach { (key, value) ->
            assertEquals(value, retrieved?.get(key))
        }
    }

    @Test
    fun `null and empty value handling`() {
        val entity = 1
        val props =
            mapOf(
                "nullValue" to NullVal,
                "emptyString" to "".strVal,
                "normalValue" to "normal".strVal,
            )
        entityPropertyMap.put(entity, props)
        val retrieved = entityPropertyMap[entity]
        assertEquals(NullVal, retrieved?.get("nullValue"))
        assertEquals("".strVal, retrieved?.get("emptyString"))
    }

    @Test
    fun `large data set handling`() {
        val entityCount = 1000
        val propsPerEntity = 50

        for (i in 1..entityCount) {
            val props = (1..propsPerEntity).associate { "prop$it" to "value$it for entity$i".strVal }
            entityPropertyMap.put(i, props)
        }

        assertEquals(entityCount, entityPropertyMap.size)

        listOf(1, 250, 500, 750, 1000).forEach { i ->
            val props = entityPropertyMap[i]
            assertNotNull(props)
            assertEquals(propsPerEntity, props.size)
        }
    }

    // -- Bulk operations --

    @Test
    fun `bulk operations`() {
        val entities =
            mapOf(
                1 to mapOf("name" to "Entity 1".strVal),
                2 to mapOf("name" to "Entity 2".strVal),
                3 to mapOf("name" to "Entity 3".strVal),
            )
        entityPropertyMap.putAll(entities)
        assertEquals(3, entityPropertyMap.size)
        entities.forEach { (entity, props) ->
            assertEquals<Map<String, IValue>>(props, entityPropertyMap[entity]!!)
        }
    }

    @Test
    fun `collection view operations`() {
        val entities =
            mapOf(
                1 to mapOf("name" to "Entity 1".strVal),
                2 to mapOf("name" to "Entity 2".strVal),
            )
        entityPropertyMap.putAll(entities)

        assertEquals(2, entityPropertyMap.keys.size)
        assertTrue(entityPropertyMap.keys.containsAll(entities.keys))

        assertEquals(2, entityPropertyMap.values.size)
        assertEquals(2, entityPropertyMap.entries.size)
    }
}
