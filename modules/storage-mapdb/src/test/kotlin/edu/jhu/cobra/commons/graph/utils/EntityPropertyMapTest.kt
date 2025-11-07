package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
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
    private lateinit var entityPropertyMap: EntityPropertyMap<NodeID>

    @Before
    fun setUp() {
        dbManager = DBMaker.memoryDB().make()
        entityPropertyMap = EntityPropertyMap(dbManager, "test-entity-props")
    }

    @After
    fun tearDown() {
        dbManager.close()
    }

    // =============== Basic Operations Tests ===============

    @Test
    fun `test basic put and get operations`() {
        val entity = NodeID("entity1")
        val props = mapOf(
            "name" to "Entity 1".strVal,
            "count" to 42.numVal,
            "active" to true.boolVal
        )

        assertNull(entityPropertyMap.put(entity, props), "Should return null for new entity")
        assertEquals(props, entityPropertyMap[entity], "Should retrieve correct properties")
    }

    @Test
    fun `test property update and override`() {
        val entity = NodeID("entity1")
        val initialProps = mapOf("name" to "Initial".strVal)
        val updatedProps = mapOf(
            "name" to "Updated".strVal,
            "newProp" to "new value".strVal
        )

        entityPropertyMap.put(entity, initialProps)
        assertEquals(initialProps, entityPropertyMap.put(entity, updatedProps), "Should return previous properties")
        assertEquals(updatedProps, entityPropertyMap[entity], "Should update properties correctly")
    }

    @Test
    fun `test entity removal`() {
        val entity = NodeID("entity1")
        val props = mapOf("name" to "Entity 1".strVal)

        entityPropertyMap.put(entity, props)
        assertEquals(props, entityPropertyMap.remove(entity), "Should return removed properties")
        assertNull(entityPropertyMap[entity], "Entity should be removed")
        assertFalse(entityPropertyMap.containsKey(entity), "Entity should not exist")
    }

    // =============== Property Map Operations Tests ===============

    @Test
    fun `test property map operations`() {
        val entity = NodeID("entity1")
        val initialProps = mapOf(
            "key1" to "value1".strVal,
            "key2" to "value2".strVal
        )
        entityPropertyMap.put(entity, initialProps)
        val propertyMap = entityPropertyMap[entity] as MutableMap<String, IValue>

        // Test property addition
        propertyMap["key3"] = "value3".strVal
        assertEquals("value3".strVal, propertyMap["key3"], "Should add new property")

        // Test property update
        propertyMap["key1"] = "updated".strVal
        assertEquals("updated".strVal, propertyMap["key1"], "Should update existing property")

        // Test property removal
        propertyMap.remove("key2")
        assertFalse(propertyMap.containsKey("key2"), "Should remove property")
    }

    @Test
    fun `test property map collection views`() {
        val entity = NodeID("entity1")
        val props = mapOf(
            "key1" to "value1".strVal,
            "key2" to "value2".strVal,
            "key3" to "value3".strVal
        )
        entityPropertyMap.put(entity, props)
        val propertyMap = entityPropertyMap[entity] as MutableMap<String, IValue>

        // Test entries view
        val entries = propertyMap.entries
        assertEquals(3, entries.size, "Should have correct number of entries")
        assertTrue(entries.any { it.key == "key1" && it.value == "value1".strVal })

        // Test keys view
        val keys = propertyMap.keys
        assertEquals(3, keys.size, "Should have correct number of keys")
        assertTrue(keys.containsAll(listOf("key1", "key2", "key3")))

        // Test values view
        val values = propertyMap.values
        assertEquals(3, values.size, "Should have correct number of values")
        assertTrue(values.containsAll(listOf("value1".strVal, "value2".strVal, "value3".strVal)))
    }

    // =============== Edge Cases Tests ===============

    @Test
    fun `test edge cases for property names and values`() {
        val entity = NodeID("entity1")
        val edgeCases = mapOf(
            "" to "Empty key".strVal,
            "!@#$%^&*()" to "Special chars".strVal,
            "你好世界" to "Unicode chars".strVal,
            "a:b" to "Colon in key".strVal,
            "key with spaces" to "Spaces in key".strVal,
            "key\nwith\nnewlines" to "Newlines in key".strVal
        )

        entityPropertyMap.put(entity, edgeCases)
        val retrieved = entityPropertyMap[entity]
        edgeCases.forEach { (key, value) ->
            assertEquals(value, retrieved?.get(key), "Should handle edge case key: $key")
        }
    }

    @Test
    fun `test null and empty value handling`() {
        val entity = NodeID("entity1")
        val props = mapOf(
            "nullValue" to NullVal,
            "emptyString" to "".strVal,
            "normalValue" to "normal".strVal
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

        // Create and add large number of entities
        for (i in 1..entityCount) {
            val entity = NodeID("entity$i")
            val props = (1..propsPerEntity).associate {
                "prop$it" to "value$it for entity$i".strVal
            }
            entityPropertyMap.put(entity, props)
        }

        assertEquals(entityCount, entityPropertyMap.size, "Should handle large number of entities")

        // Verify random entities
        listOf(1, 250, 500, 750, 1000).forEach { i ->
            val entity = NodeID("entity$i")
            val props = entityPropertyMap[entity]
            assertNotNull(props, "Should retrieve entity$i")
            assertEquals(propsPerEntity, props.size, "entity$i should have correct number of properties")
        }
    }

    // =============== Collection Operation Tests ===============

    @Test
    fun `test bulk operations`() {
        val entities = mapOf(
            NodeID("entity1") to mapOf("name" to "Entity 1".strVal),
            NodeID("entity2") to mapOf("name" to "Entity 2".strVal),
            NodeID("entity3") to mapOf("name" to "Entity 3".strVal)
        )

        entityPropertyMap.putAll(entities)
        assertEquals(3, entityPropertyMap.size, "Should add all entities")
        entities.forEach { (entity, props) ->
            assertEquals(props, entityPropertyMap[entity], "Should store correct properties for $entity")
        }
    }

    @Test
    fun `test collection view operations`() {
        val entities = mapOf(
            NodeID("entity1") to mapOf("name" to "Entity 1".strVal),
            NodeID("entity2") to mapOf("name" to "Entity 2".strVal)
        )
        entityPropertyMap.putAll(entities)

        // Test keys view
        val keys = entityPropertyMap.keys
        assertEquals(2, keys.size, "Should have correct number of keys")
        assertTrue(keys.containsAll(entities.keys))

        // Test values view
        val values = entityPropertyMap.values
        assertEquals(2, values.size, "Should have correct number of values")
        assertTrue(values.containsAll(entities.values))

        // Test entries view
        val entries = entityPropertyMap.entries
        assertEquals(2, entries.size, "Should have correct number of entries")
        entities.forEach { (entity, props) ->
            assertTrue(entries.any { it.key == entity && it.value == props })
        }
    }

    // =============== Error Handling Tests ===============

    @Test
    fun `test error handling for non-existent entities`() {
        val nonExistentEntity = NodeID("nonexistent")
        assertNull(entityPropertyMap[nonExistentEntity], "Should return null for non-existent entity")
        assertNull(entityPropertyMap.remove(nonExistentEntity), "Should return null when removing non-existent entity")
    }

    @Test
    fun `test error handling for closed database`() {
        val entity = NodeID("entity1")
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
            entityPropertyMap.keys.add(NodeID("newEntity"))
        }
        assertFailsWith<UnsupportedOperationException> {
            entityPropertyMap.values.add(mapOf("key" to "value".strVal))
        }
    }

    // =============== Concurrent Modification Tests ===============

    @Test
    fun `test concurrent modification handling`() {
        val entity = NodeID("entity1")
        val initialProps = mapOf(
            "key1" to "value1".strVal,
            "key2" to "value2".strVal,
            "key3" to "value3".strVal
        )
        entityPropertyMap.put(entity, initialProps)
        val propertyMap = entityPropertyMap[entity] as MutableMap<String, IValue>

        // Test iterator safety
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

        // 1. 写入数据
        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap<NodeID>(db, "persist-test")
            val entity = NodeID("persist-entity")
            val props = mapOf(
                "name" to "Persistent".strVal,
                "count" to 123.numVal,
                "unicode" to "测试".strVal,
                "null" to NullVal
            )
            map.put(entity, props)
            db.close()
        }

        // 2. 重新打开数据库，验证数据
        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap<NodeID>(db, "persist-test")
            val entity = NodeID("persist-entity")
            val props = map[entity]
            assertNotNull(props)
            assertEquals("Persistent".strVal, props["name"])
            assertEquals(123.numVal, props["count"])
            assertEquals("测试".strVal, props["unicode"])
            assertEquals(NullVal, props["null"])
            db.close()
        }

        // 清理
        Files.deleteIfExists(dbFile.toPath())
        Files.deleteIfExists(tmpDir)
    }

    @Test
    fun `test large scale persistence and reload`() {
        val tmpDir = createTempDirectory("entity_prop_map_large_test")
        val dbFile = tmpDir.resolve("large.db").toFile()
        val entityCount = 500
        val propsPerEntity = 20

        // 写入大数据
        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap<NodeID>(db, "large-test")
            for (i in 1..entityCount) {
                val entity = NodeID("entity$i")
                val props = (1..propsPerEntity).associate { "prop$it" to "val${i}_$it".strVal }
                map.put(entity, props)
            }
            db.close()
        }

        // 重新打开验证
        run {
            val db = DBMaker.fileDB(dbFile).make()
            val map = EntityPropertyMap<NodeID>(db, "large-test")
            for (i in listOf(1, entityCount / 2, entityCount)) {
                val entity = NodeID("entity$i")
                val props = map[entity]
                assertNotNull(props)
                assertEquals(propsPerEntity, props.size)
                assertEquals("val${i}_1".strVal, props["prop1"])
            }
            db.close()
        }

        // 清理
        Files.deleteIfExists(dbFile.toPath())
        Files.deleteIfExists(tmpDir)
    }
}