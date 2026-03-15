package edu.jhu.cobra.commons.graph.utils

import org.mapdb.DBMaker
import org.mapdb.Serializer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapDbIDSerializerTest {
    @Test
    fun `test serialize and deserialize Int ID`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        val id = 42
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test multiple IDs in same map`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        val nodeId = 1
        val edgeId = 2

        map["node"] = nodeId
        map["edge"] = edgeId

        assertEquals(nodeId, map["node"])
        assertEquals(edgeId, map["edge"])
        db.close()
    }

    @Test
    fun `test persistence across database sessions`() {
        val tmpDirectory = createTempDirectory("mapdb_test")
        val dbFile = tmpDirectory.resolve("test.db").toFile()

        val db = DBMaker.fileDB(dbFile).make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        val nodeId = 100
        val edgeId = 200

        map["node"] = nodeId
        map["edge"] = edgeId
        db.close()

        val db2 = DBMaker.fileDB(dbFile).make()
        val map2 = db2.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        assertEquals(nodeId, map2["node"])
        assertEquals(edgeId, map2["edge"])
        db2.close()
    }

    @Test
    fun `test ID with zero value`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        val id = 0
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test ID with negative value`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        val id = -1
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test ID with max int value`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        val id = Int.MAX_VALUE
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test ID with min int value`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer()).createOrOpen()
        val id = Int.MIN_VALUE
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test ID in hashSet`() {
        val db = DBMaker.memoryDB().make()
        val set = db.hashSet("test", MapDbIDSerializer()).createOrOpen()
        val id1 = 1
        val id2 = 2

        set.add(id1)
        set.add(id2)

        assertTrue(set.contains(id1))
        assertTrue(set.contains(id2))
        db.close()
    }
}
