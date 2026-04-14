/**
 * Tests for [MapDbIDSerializer] round-trip serialization.
 *
 * - `serialize and deserialize Int ID`
 * - `multiple IDs in same map`
 * - `persistence across database sessions`
 * - `ID with zero value`
 * - `ID with negative value`
 * - `ID with max int value`
 * - `ID with min int value`
 * - `ID in hashSet`
 */
package edu.jhu.cobra.commons.graph.utils

import org.mapdb.DBMaker
import org.mapdb.Serializer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class MapDbIDSerializerTest {
    @Test
    fun `serialize and deserialize Int ID`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        val id = 42
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `multiple IDs in same map`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        map["node"] = 1
        map["edge"] = 2
        assertEquals(1, map["node"])
        assertEquals(2, map["edge"])
        db.close()
    }

    @Test
    fun `persistence across database sessions`() {
        val tmpDir = createTempDirectory("mapdb_test")
        val dbFile = tmpDir.resolve("test.db").toFile()

        val db = DBMaker.fileDB(dbFile).make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        map["node"] = 100
        map["edge"] = 200
        db.close()

        val db2 = DBMaker.fileDB(dbFile).make()
        val map2 = db2.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        assertEquals(100, map2["node"])
        assertEquals(200, map2["edge"])
        db2.close()
    }

    @Test
    fun `ID with zero value`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        map["key"] = 0
        assertEquals(0, map["key"])
        db.close()
    }

    @Test
    fun `ID with negative value`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        map["key"] = -1
        assertEquals(-1, map["key"])
        db.close()
    }

    @Test
    fun `ID with max int value`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        map["key"] = Int.MAX_VALUE
        assertEquals(Int.MAX_VALUE, map["key"])
        db.close()
    }

    @Test
    fun `ID with min int value`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        map["key"] = Int.MIN_VALUE
        assertEquals(Int.MIN_VALUE, map["key"])
        db.close()
    }

    @Test
    fun `ID in hashSet`() {
        val db = DBMaker.memoryDB().make()
        val set = db.hashSet("test", MapDbIDSerializer()).createOrOpen()
        set.add(1)
        set.add(2)
        assertTrue(set.contains(1))
        assertTrue(set.contains(2))
        db.close()
    }
}
