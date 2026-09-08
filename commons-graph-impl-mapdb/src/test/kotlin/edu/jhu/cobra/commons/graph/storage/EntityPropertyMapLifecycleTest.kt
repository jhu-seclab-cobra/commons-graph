package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.intVal
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

/*
 * Tests for EntityPropertyMap: error handling, concurrent modification, and persistence across reopen.
 *
 * - `error handling for non-existent entities`
 * - `error handling for closed database`
 * - `unsupported operations`
 * - `concurrent modification handling`
 * - `persistence and reload from fileDB`
 * - `large scale persistence and reload`
 */
internal class EntityPropertyMapLifecycleTest {
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
                    "count" to 123.intVal,
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
            assertEquals(123.intVal, props["count"])
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
