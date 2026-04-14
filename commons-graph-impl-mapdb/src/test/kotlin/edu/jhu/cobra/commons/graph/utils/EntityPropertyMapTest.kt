/**
 * Tests for [EntityPropertyMap] backed by MapDB.
 *
 * - `basic put and get operations`
 * - `property update and override`
 * - `entity removal`
 * - `property map operations`
 * - `property map collection views`
 * - `edge cases for property names and values`
 * - `null and empty value handling`
 * - `large data set handling`
 * - `bulk operations`
 * - `collection view operations`
 * - `property map entries iterator traverses all entries`
 * - `property map entries iterator remove`
 * - `property map entries iterator throws NoSuchElementException at end`
 * - `property map entries contains and containsAll`
 * - `property map entries add and addAll`
 * - `property map entries removeAll and retainAll`
 * - `property map entries retainAll keeps only specified`
 * - `property map keys iterator traverses all keys`
 * - `property map keys contains and containsAll`
 * - `property map keys add and remove`
 * - `property map keys removeAll and retainAll`
 * - `property map keys retainAll keeps specified`
 * - `property map entry setValue updates property`
 * - `entity map entries iterator traverses all entities`
 * - `entity map entries iterator remove deletes entity`
 * - `entity map entries iterator throws NoSuchElementException at end`
 * - `entity map entries setValue updates entity properties`
 * - `entity map entries add inserts new entity`
 * - `error handling for non-existent entities`
 * - `error handling for closed database`
 * - `unsupported operations`
 * - `concurrent modification handling`
 * - `persistence and reload from fileDB`
 * - `large scale persistence and reload`
 */
package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import org.mapdb.DB
import org.mapdb.DBMaker
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val props = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal,
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
        val edgeCases = mapOf(
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
        val props = mapOf(
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
        val entities = mapOf(
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
        val entities = mapOf(
            1 to mapOf("name" to "Entity 1".strVal),
            2 to mapOf("name" to "Entity 2".strVal),
        )
        entityPropertyMap.putAll(entities)

        assertEquals(2, entityPropertyMap.keys.size)
        assertTrue(entityPropertyMap.keys.containsAll(entities.keys))

        assertEquals(2, entityPropertyMap.values.size)
        assertEquals(2, entityPropertyMap.entries.size)
    }

    // -- PropertyMap inner collection coverage --

    @Test
    fun `property map entries iterator traverses all entries`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!
        val collected = mutableListOf<String>()
        val iter = pm.entries.iterator()
        while (iter.hasNext()) {
            collected.add(iter.next().key)
        }
        assertEquals(3, collected.size)
        assertTrue(collected.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `property map entries iterator remove`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal))
        val pm = entityPropertyMap[1]!! as MutableMap<String, IValue>
        val iter = pm.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key == "a") iter.remove()
        }
        assertEquals(1, pm.size)
        assertFalse(pm.containsKey("a"))
        assertTrue(pm.containsKey("b"))
    }

    @Test
    fun `property map entries iterator throws NoSuchElementException at end`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        val pm = entityPropertyMap[1]!!
        val iter = pm.entries.iterator()
        iter.next()
        assertFailsWith<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `property map entries contains and containsAll`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal))
        val pm = entityPropertyMap[1]!!
        val entries = pm.entries
        val matchEntry = entries.first { it.key == "a" }
        assertTrue(entries.contains(matchEntry))
        assertTrue(entries.containsAll(listOf(matchEntry)))
    }

    @Test
    fun `property map entries add and addAll`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        val pm = entityPropertyMap[1]!!
        val entries = pm.entries
        val newEntry = object : MutableMap.MutableEntry<String, IValue> {
            override val key = "b"
            override val value = "2".strVal
            override fun setValue(newValue: IValue) = "2".strVal
        }
        assertTrue(entries.add(newEntry))
        assertTrue(pm.containsKey("b"))
    }

    @Test
    fun `property map entries removeAll and retainAll`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!
        val entries = pm.entries
        val toRemove = entries.filter { it.key == "a" }
        assertTrue(entries.removeAll(toRemove))
        assertEquals(2, pm.size)
        assertFalse(pm.containsKey("a"))
    }

    @Test
    fun `property map entries retainAll keeps only specified`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!
        val entries = pm.entries
        val toRetain = entries.filter { it.key == "b" }
        entries.retainAll(toRetain)
        assertEquals(1, pm.size)
        assertTrue(pm.containsKey("b"))
    }

    @Test
    fun `property map keys iterator traverses all keys`() {
        entityPropertyMap.put(1, mapOf("x" to "1".strVal, "y" to "2".strVal))
        val pm = entityPropertyMap[1]!!
        val collected = mutableListOf<String>()
        val iter = pm.keys.iterator()
        while (iter.hasNext()) {
            collected.add(iter.next())
        }
        assertEquals(2, collected.size)
        assertTrue(collected.containsAll(listOf("x", "y")))
    }

    @Test
    fun `property map keys contains and containsAll`() {
        entityPropertyMap.put(1, mapOf("x" to "1".strVal, "y" to "2".strVal))
        val pm = entityPropertyMap[1]!!
        val keys = pm.keys
        assertTrue(keys.contains("x"))
        assertTrue(keys.containsAll(listOf("x", "y")))
        assertFalse(keys.contains("z"))
    }

    @Test
    fun `property map keys add and remove`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        val pm = entityPropertyMap[1]!!
        val keys = pm.keys
        keys.add("b")
        assertTrue(pm.containsKey("b"))
        keys.remove("a")
        assertFalse(pm.containsKey("a"))
    }

    @Test
    fun `property map keys removeAll and retainAll`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!
        pm.keys.removeAll(listOf("a", "c"))
        assertEquals(1, pm.size)
        assertTrue(pm.containsKey("b"))
    }

    @Test
    fun `property map keys retainAll keeps specified`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!
        pm.keys.retainAll(listOf("b"))
        assertEquals(1, pm.size)
        assertTrue(pm.containsKey("b"))
    }

    @Test
    fun `property map entry setValue updates property`() {
        entityPropertyMap.put(1, mapOf("a" to "old".strVal))
        val pm = entityPropertyMap[1]!!
        val entry = pm.entries.first()
        entry.setValue("new".strVal)
        assertEquals("new".strVal, pm["a"])
    }

    // -- EntityPropertyMap entries coverage --

    @Test
    fun `entity map entries iterator traverses all entities`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        entityPropertyMap.put(2, mapOf("b" to "2".strVal))
        val collected = mutableListOf<Int>()
        val iter = entityPropertyMap.entries.iterator()
        while (iter.hasNext()) {
            collected.add(iter.next().key)
        }
        assertEquals(2, collected.size)
    }

    @Test
    fun `entity map entries iterator remove deletes entity`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        entityPropertyMap.put(2, mapOf("b" to "2".strVal))
        val iter = entityPropertyMap.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key == 1) iter.remove()
        }
        assertEquals(1, entityPropertyMap.size)
        assertFalse(entityPropertyMap.containsKey(1))
    }

    @Test
    fun `entity map entries iterator throws NoSuchElementException at end`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        val iter = entityPropertyMap.entries.iterator()
        iter.next()
        assertFailsWith<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `entity map entries setValue updates entity properties`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        val entry = entityPropertyMap.entries.first()
        entry.setValue(mapOf("b" to "2".strVal))
        val props = entityPropertyMap[1]!!
        assertTrue(props.containsKey("b"))
        assertEquals("2".strVal, props["b"])
    }

    @Test
    fun `entity map entries add inserts new entity`() {
        val entry = object : MutableMap.MutableEntry<Int, Map<String, IValue>> {
            override val key = 5
            override val value = mapOf("x" to "v".strVal)
            override fun setValue(newValue: Map<String, IValue>) = value
        }
        entityPropertyMap.entries.add(entry)
        assertTrue(entityPropertyMap.containsKey(5))
        assertEquals("v".strVal, entityPropertyMap[5]!!["x"])
    }

    // -- Error handling --

    @Test
    fun `error handling for non-existent entities`() {
        assertNull(entityPropertyMap[-1])
        assertNull(entityPropertyMap.remove(-1))
    }

    @Test
    fun `error handling for closed database`() {
        entityPropertyMap.put(1, mapOf("key" to "value".strVal))
        dbManager.close()
        assertFailsWith<IllegalAccessError> {
            entityPropertyMap.put(1, mapOf("key2" to "value2".strVal))
        }
        assertFailsWith<IllegalAccessError> {
            entityPropertyMap[1]
        }
    }

    @Test
    fun `unsupported operations`() {
        assertFailsWith<UnsupportedOperationException> {
            entityPropertyMap.keys.add(99)
        }
        assertFailsWith<UnsupportedOperationException> {
            entityPropertyMap.values.add(mapOf("key" to "value".strVal))
        }
    }

    // -- Concurrent modification --

    @Test
    fun `concurrent modification handling`() {
        entityPropertyMap.put(
            1,
            mapOf("key1" to "value1".strVal, "key2" to "value2".strVal, "key3" to "value3".strVal),
        )
        val propertyMap = entityPropertyMap[1] as MutableMap<String, IValue>
        val iterator = propertyMap.entries.iterator()
        var count = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            count++
            if (entry.key == "key2") iterator.remove()
        }
        assertEquals(3, count)
        assertEquals(2, propertyMap.size)
        assertFalse(propertyMap.containsKey("key2"))
    }

    // -- Persistence --

    @Test
    fun `persistence and reload from fileDB`() {
        val tmpDir = createTempDirectory("entity_prop_map_test")
        val dbFile = tmpDir.resolve("test.db").toFile()

        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap(db, "persist-test")
            map.put(
                1,
                mapOf(
                    "name" to "Persistent".strVal,
                    "count" to 123.numVal,
                    "null" to NullVal,
                ),
            )
            db.close()
        }

        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap(db, "persist-test")
            val props = map[1]
            assertNotNull(props)
            assertEquals("Persistent".strVal, props["name"])
            assertEquals(123.numVal, props["count"])
            assertEquals(NullVal, props["null"])
            db.close()
        }

        Files.deleteIfExists(dbFile.toPath())
        Files.deleteIfExists(tmpDir)
    }

    @Test
    fun `large scale persistence and reload`() {
        val tmpDir = createTempDirectory("entity_prop_map_large_test")
        val dbFile = tmpDir.resolve("large.db").toFile()
        val entityCount = 500
        val propsPerEntity = 20

        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap(db, "large-test")
            for (i in 1..entityCount) {
                val props = (1..propsPerEntity).associate { "prop$it" to "val${i}_$it".strVal }
                map.put(i, props)
            }
            db.close()
        }

        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap(db, "large-test")
            for (i in listOf(1, entityCount / 2, entityCount)) {
                val props = map[i]
                assertNotNull(props)
                assertEquals(propsPerEntity, props.size)
                assertEquals("val${i}_1".strVal, props["prop1"])
            }
            db.close()
        }

        Files.deleteIfExists(dbFile.toPath())
        Files.deleteIfExists(tmpDir)
    }
}
