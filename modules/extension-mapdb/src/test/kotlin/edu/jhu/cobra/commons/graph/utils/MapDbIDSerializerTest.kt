package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.graph.entity.NodeID
import org.mapdb.DBMaker
import org.mapdb.Serializer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapDbIDSerializerTest {
    @Test
    fun `test serialize and deserialize NodeID`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer<NodeID>()).createOrOpen()
        val id = NodeID("test_node")
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test multiple IDs in same map`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer<IEntity.ID>()).createOrOpen()
        val nodeId = NodeID("node1")
        val edgeId = EdgeID(NodeID("node1"), NodeID("node1"), "edge1")

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
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer<IEntity.ID>()).createOrOpen()
        val nodeId = NodeID("persistent_node")
        val edgeId = EdgeID(NodeID("persistent_node"), NodeID("persistent_node"), "persistent_edge")


        map["node"] = nodeId
        map["edge"] = edgeId
        db.close()

        val db2 = DBMaker.fileDB(dbFile).make()
        val map2 = db2.hashMap("test", Serializer.STRING, MapDbIDSerializer<IEntity.ID>()).createOrOpen()
        assertEquals(nodeId, map2["node"])
        assertEquals(edgeId, map2["edge"])
        db2.close()
    }

    @Test
    fun `test ID with special characters`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer<NodeID>()).createOrOpen()
        val id = NodeID("special@#$%^&*()_+chars")
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test ID with unicode characters`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer<NodeID>()).createOrOpen()
        val id = NodeID("unicode测试字符")
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test ID with empty string`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer<NodeID>()).createOrOpen()
        val id = NodeID("")
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test ID with very long string`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbIDSerializer<NodeID>()).createOrOpen()
        val longString = "a".repeat(1000)
        val id = NodeID(longString)
        map["key"] = id
        assertEquals(id, map["key"])
        db.close()
    }

    @Test
    fun `test ID in hashSet`() {
        val db = DBMaker.memoryDB().make()
        val set = db.hashSet("test", MapDbIDSerializer<NodeID>()).createOrOpen()
        val id1 = NodeID("node1")
        val id2 = NodeID("node2")

        set.add(id1)
        set.add(id2)

        assertTrue(set.contains(id1))
        assertTrue(set.contains(id2))
        db.close()
    }
}

