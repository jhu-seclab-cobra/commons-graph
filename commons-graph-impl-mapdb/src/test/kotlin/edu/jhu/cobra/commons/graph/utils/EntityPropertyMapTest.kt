package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.value.*
import org.mapdb.DB
import org.mapdb.DBMaker
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * Test the functionality of MapDB implementation of EntityPropertyMap
 */
class EntityPropertyMapTest {
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

    // =============== Basic Operations Tests ===============

    @Test
    fun `test basic put and get operations`() {
        val entity = 1
        val props =
            mapOf(
                "name" to "Entity 1".strVal,
                "count" to 42.numVal,
                "active" to true.boolVal,
            )

        assertNull(entityPropertyMap.put(entity, props), "Should return null for new entity")
        assertEquals<Map<String, IValue>>(props, entityPropertyMap[entity]!!, "Should retrieve correct properties")
    }

    @Test
    fun `test property update and override`() {
        val entity = 1
        val initialProps = mapOf("name" to "Initial".strVal)
        val updatedProps =
            mapOf(
                "name" to "Updated".strVal,
                "newProp" to "new value".strVal,
            )

        entityPropertyMap.put(entity, initialProps)
        assertEquals<Map<String, IValue>>(initialProps, entityPropertyMap.put(entity, updatedProps)!!, "Should return previous properties")
        assertEquals<Map<String, IValue>>(updatedProps, entityPropertyMap[entity]!!, "Should update properties correctly")
    }

    @Test
    fun `test entity removal`() {
        val entity = 1
        val props = mapOf("name" to "Entity 1".strVal)

        entityPropertyMap.put(entity, props)
        assertEquals(props, entityPropertyMap.remove(entity), "Should return removed properties")
        assertNull(entityPropertyMap[entity], "Entity should be removed")
        assertFalse(entityPropertyMap.containsKey(entity), "Entity should not exist")
    }

    // =============== Property Map Operations Tests ===============

    @Test
    fun `test property map operations`() {
        val entity = 1
        val initialProps =
            mapOf(
                "key1" to "value1".strVal,
                "key2" to "value2".strVal,
            )
        entityPropertyMap.put(entity, initialProps)
        val propertyMap = entityPropertyMap[entity]!!

        propertyMap["key3"] = "value3".strVal
        assertEquals("value3".strVal, propertyMap["key3"], "Should add new property")

        propertyMap["key1"] = "updated".strVal
        assertEquals("updated".strVal, propertyMap["key1"], "Should update existing property")

        propertyMap.remove("key2")
        assertFalse(propertyMap.containsKey("key2"), "Should remove property")
    }

    @Test
    fun `test property map collection views`() {
        val entity = 1
        val props =
            mapOf(
                "key1" to "value1".strVal,
                "key2" to "value2".strVal,
                "key3" to "value3".strVal,
            )
        entityPropertyMap.put(entity, props)
        val propertyMap = entityPropertyMap[entity]!!

        val entries = propertyMap.entries
        assertEquals(3, entries.size, "Should have correct number of entries")
        assertTrue(entries.any { it.key == "key1" && it.value == "value1".strVal })

        val keys = propertyMap.keys
        assertEquals(3, keys.size, "Should have correct number of keys")
        assertTrue(keys.containsAll(listOf("key1", "key2", "key3")))

        val values = propertyMap.values
        assertEquals(3, values.size, "Should have correct number of values")
        assertTrue(values.containsAll(listOf("value1".strVal, "value2".strVal, "value3".strVal)))
    }

    // =============== Edge Cases Tests ===============

    @Test
    fun `test edge cases for property names and values`() {
        val entity = 1
        val edgeCases =
            mapOf(
                "" to "Empty key".strVal,
                "!@#\$%^&*()" to "Special chars".strVal,
                "你好世界" to "Unicode chars".strVal,
                "a:b" to "Colon in key".strVal,
                "key with spaces" to "Spaces in key".strVal,
                "key\nwith\nnewlines" to "Newlines in key".strVal,
            )

        entityPropertyMap.put(entity, edgeCases)
        val retrieved = entityPropertyMap[entity]
        edgeCases.forEach { (key, value) ->
            assertEquals(value, retrieved?.get(key), "Should handle edge case key: $key")
        }
    }

    @Test
    fun `test null and empty value handling`() {
        val entity = 1
        val props =
            mapOf(
                "nullValue" to NullVal,
                "emptyString" to "".strVal,
                "normalValue" to "normal".strVal,
            )

        entityPropertyMap.put(entity, props)
        val retrieved = entityPropertyMap[entity]
        assertEquals(NullVal, retrieved?.get("nullValue"), "Should handle NullVal")
        assertEquals("".strVal, retrieved?.get("emptyString"), "Should handle empty string")
    }

    @Test
    fun `test large data set handling`() {
        val entityCount = 1000
        val propsPerEntity = 50

        for (i in 1..entityCount) {
            val props =
                (1..propsPerEntity).associate {
                    "prop$it" to "value$it for entity$i".strVal
                }
            entityPropertyMap.put(i, props)
        }

        assertEquals(entityCount, entityPropertyMap.size, "Should handle large number of entities")

        listOf(1, 250, 500, 750, 1000).forEach { i ->
            val props = entityPropertyMap[i]
            assertNotNull(props, "Should retrieve entity $i")
            assertEquals(propsPerEntity, props.size, "entity $i should have correct number of properties")
        }
    }

    // =============== Collection Operation Tests ===============

    @Test
    fun `test bulk operations`() {
        val entities =
            mapOf(
                1 to mapOf("name" to "Entity 1".strVal),
                2 to mapOf("name" to "Entity 2".strVal),
                3 to mapOf("name" to "Entity 3".strVal),
            )

        entityPropertyMap.putAll(entities)
        assertEquals(3, entityPropertyMap.size, "Should add all entities")
        entities.forEach { (entity, props) ->
            assertEquals<Map<String, IValue>>(props, entityPropertyMap[entity]!!, "Should store correct properties for $entity")
        }
    }

    @Test
    fun `test collection view operations`() {
        val entities =
            mapOf(
                1 to mapOf("name" to "Entity 1".strVal),
                2 to mapOf("name" to "Entity 2".strVal),
            )
        entityPropertyMap.putAll(entities)

        val keys = entityPropertyMap.keys
        assertEquals(2, keys.size, "Should have correct number of keys")
        assertTrue(keys.containsAll(entities.keys))

        val values = entityPropertyMap.values
        assertEquals(2, values.size, "Should have correct number of values")
        assertTrue(values.containsAll(entities.values))

        val entries = entityPropertyMap.entries
        assertEquals(2, entries.size, "Should have correct number of entries")
        entities.forEach { (entity, props) ->
            assertTrue(entries.any { it.key == entity && it.value == props })
        }
    }

    // =============== PropertyMap Inner Collection Coverage ===============

    @Test
    fun `test property map entries iterator traverses all entries`() {
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
    fun `test property map entries iterator remove`() {
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
    fun `test property map entries iterator throws NoSuchElementException at end`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        val pm = entityPropertyMap[1]!!

        val iter = pm.entries.iterator()
        iter.next()
        assertFailsWith<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `test property map entries contains and containsAll`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal))
        val pm = entityPropertyMap[1]!!

        val entries = pm.entries
        val matchEntry = entries.first { it.key == "a" }
        assertTrue(entries.contains(matchEntry))
        assertTrue(entries.containsAll(listOf(matchEntry)))
    }

    @Test
    fun `test property map entries add and addAll`() {
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
    fun `test property map entries removeAll and retainAll`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!

        val entries = pm.entries
        val toRemove = entries.filter { it.key == "a" }
        assertTrue(entries.removeAll(toRemove))
        assertEquals(2, pm.size)
        assertFalse(pm.containsKey("a"))
    }

    @Test
    fun `test property map entries retainAll keeps only specified`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!

        val entries = pm.entries
        val toRetain = entries.filter { it.key == "b" }
        entries.retainAll(toRetain)

        assertEquals(1, pm.size)
        assertTrue(pm.containsKey("b"))
    }

    @Test
    fun `test property map keys iterator traverses all keys`() {
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
    fun `test property map keys contains and containsAll`() {
        entityPropertyMap.put(1, mapOf("x" to "1".strVal, "y" to "2".strVal))
        val pm = entityPropertyMap[1]!!

        val keys = pm.keys
        assertTrue(keys.contains("x"))
        assertTrue(keys.containsAll(listOf("x", "y")))
        assertFalse(keys.contains("z"))
    }

    @Test
    fun `test property map keys add and remove`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))
        val pm = entityPropertyMap[1]!!

        val keys = pm.keys
        keys.add("b")
        assertTrue(pm.containsKey("b"))

        keys.remove("a")
        assertFalse(pm.containsKey("a"))
    }

    @Test
    fun `test property map keys removeAll and retainAll`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!

        pm.keys.removeAll(listOf("a", "c"))
        assertEquals(1, pm.size)
        assertTrue(pm.containsKey("b"))
    }

    @Test
    fun `test property map keys retainAll keeps specified`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal, "b" to "2".strVal, "c" to "3".strVal))
        val pm = entityPropertyMap[1]!!

        pm.keys.retainAll(listOf("b"))
        assertEquals(1, pm.size)
        assertTrue(pm.containsKey("b"))
    }

    @Test
    fun `test property map entry setValue updates property`() {
        entityPropertyMap.put(1, mapOf("a" to "old".strVal))
        val pm = entityPropertyMap[1]!!

        val entry = pm.entries.first()
        entry.setValue("new".strVal)

        assertEquals("new".strVal, pm["a"])
    }

    // =============== EntityPropertyMap entries coverage ===============

    @Test
    fun `test entity map entries iterator traverses all entities`() {
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
    fun `test entity map entries iterator remove deletes entity`() {
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
    fun `test entity map entries iterator throws NoSuchElementException at end`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))

        val iter = entityPropertyMap.entries.iterator()
        iter.next()
        assertFailsWith<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `test entity map entries setValue updates entity properties`() {
        entityPropertyMap.put(1, mapOf("a" to "1".strVal))

        val entry = entityPropertyMap.entries.first()
        entry.setValue(mapOf("b" to "2".strVal))

        val props = entityPropertyMap[1]!!
        assertTrue(props.containsKey("b"))
        assertEquals("2".strVal, props["b"])
    }

    @Test
    fun `test entity map entries add inserts new entity`() {
        val entry = object : MutableMap.MutableEntry<Int, Map<String, IValue>> {
            override val key = 5
            override val value = mapOf("x" to "v".strVal)
            override fun setValue(newValue: Map<String, IValue>) = value
        }

        entityPropertyMap.entries.add(entry)

        assertTrue(entityPropertyMap.containsKey(5))
        assertEquals("v".strVal, entityPropertyMap[5]!!["x"])
    }

    // =============== Error Handling Tests ===============

    @Test
    fun `test error handling for non-existent entities`() {
        val nonExistentEntity = -1
        assertNull(entityPropertyMap[nonExistentEntity], "Should return null for non-existent entity")
        assertNull(entityPropertyMap.remove(nonExistentEntity), "Should return null when removing non-existent entity")
    }

    @Test
    fun `test error handling for closed database`() {
        val entity = 1
        entityPropertyMap.put(entity, mapOf("key" to "value".strVal))
        dbManager.close()

        assertFailsWith<IllegalAccessError> {
            entityPropertyMap.put(entity, mapOf("key2" to "value2".strVal))
        }
        assertFailsWith<IllegalAccessError> {
            entityPropertyMap[entity]
        }
    }

    @Test
    fun `test unsupported operations`() {
        assertFailsWith<UnsupportedOperationException> {
            entityPropertyMap.keys.add(99)
        }
        assertFailsWith<UnsupportedOperationException> {
            entityPropertyMap.values.add(mapOf("key" to "value".strVal))
        }
    }

    // =============== Concurrent Modification Tests ===============

    @Test
    fun `test concurrent modification handling`() {
        val entity = 1
        val initialProps =
            mapOf(
                "key1" to "value1".strVal,
                "key2" to "value2".strVal,
                "key3" to "value3".strVal,
            )
        entityPropertyMap.put(entity, initialProps)
        val propertyMap = entityPropertyMap[entity] as MutableMap<String, IValue>

        val iterator = propertyMap.entries.iterator()
        var count = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            count++
            if (entry.key == "key2") {
                iterator.remove()
            }
        }

        assertEquals(3, count, "Should iterate through all entries")
        assertEquals(2, propertyMap.size, "Should have correct size after removal")
        assertFalse(propertyMap.containsKey("key2"), "Should remove entry via iterator")
    }

    @Test
    fun `test persistence and reload from fileDB`() {
        val tmpDir = createTempDirectory("entity_prop_map_test")
        val dbFile = tmpDir.resolve("test.db").toFile()

        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap(db, "persist-test")
            val entity = 1
            val props =
                mapOf(
                    "name" to "Persistent".strVal,
                    "count" to 123.numVal,
                    "unicode" to "测试".strVal,
                    "null" to NullVal,
                )
            map.put(entity, props)
            db.close()
        }

        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap(db, "persist-test")
            val entity = 1
            val props = map[entity]
            assertNotNull(props)
            assertEquals("Persistent".strVal, props["name"])
            assertEquals(123.numVal, props["count"])
            assertEquals("测试".strVal, props["unicode"])
            assertEquals(NullVal, props["null"])
            db.close()
        }

        Files.deleteIfExists(dbFile.toPath())
        Files.deleteIfExists(tmpDir)
    }

    @Test
    fun `test large scale persistence and reload`() {
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
