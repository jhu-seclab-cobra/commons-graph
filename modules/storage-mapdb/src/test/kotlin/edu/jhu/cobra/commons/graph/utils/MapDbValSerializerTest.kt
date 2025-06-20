package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.value.*
import org.mapdb.DBMaker
import org.mapdb.Serializer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapDbValSerializerTest {
    @Test
    fun `test serialize and deserialize StrVal`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbValSerializer<StrVal>()).createOrOpen()
        val value = StrVal("hello world")
        map["key"] = value
        assertEquals(value, map["key"])
        db.close()
    }

    @Test
    fun `test serialize and deserialize NumVal`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbValSerializer<NumVal>()).createOrOpen()
        val value = NumVal(1234567890)
        map["key"] = value
        assertEquals(value, map["key"])
        db.close()
    }

    @Test
    fun `test serialize and deserialize BoolVal and NullVal`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbValSerializer<IValue>()).createOrOpen()
        val boolVal = BoolVal(true)
        val nullVal = NullVal
        map["bool"] = boolVal
        map["null"] = nullVal
        assertEquals(boolVal, map["bool"])
        assertEquals(nullVal, map["null"])
        db.close()
    }

    @Test
    fun `test serialize and deserialize ListVal and MapVal`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbValSerializer()).createOrOpen()
        val listVal = ListVal(listOf(StrVal("a"), NumVal(1), BoolVal(false)))
        val mapVal = MapVal("k1" to StrVal("v1"), "k2" to NumVal(2))
        map["list"] = listVal
        map["map"] = mapVal
        assertEquals(listVal, map["list"])
        assertEquals(mapVal, map["map"])
        db.close()
    }

    @Test
    fun `test serialize and deserialize nested complex values`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbValSerializer<IValue>()).createOrOpen()
        val nested = ListVal(
            listOf(
                MapVal("a" to ListVal(listOf(NumVal(1), NullVal))),
                BoolVal(true),
                StrVal("deep")
            )
        )
        map["nested"] = nested
        assertEquals(nested, map["nested"])
        db.close()
    }

    @Test
    fun `test serialize and deserialize with special characters`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbValSerializer<StrVal>()).createOrOpen()
        val value = StrVal("特殊字符\n\t,;!@#￥%……&*")
        map["key"] = value
        assertEquals(value, map["key"])
        db.close()
    }

    @Test
    fun `test repeated serialize deserialize consistency`() {
        val db = DBMaker.memoryDB().make()
        val map = db.hashMap("test", Serializer.STRING, MapDbValSerializer<ListVal>()).createOrOpen()
        val value = ListVal(listOf(StrVal("a"), NumVal(1), BoolVal(false)))
        map["key"] = value
        val first = map["key"]
        map["key"] = first
        val second = map["key"]
        assertEquals(value, second)
        db.close()
    }

    @Test
    fun `test integration with MapDB hashSet`() {
        val db = DBMaker.memoryDB().make()
        val set = db.hashSet("test", MapDbValSerializer<ListVal>()).createOrOpen()
        val v1 = ListVal(listOf(StrVal("a"), NumVal(1)))
        val v2 = ListVal(listOf(BoolVal(true), NullVal))
        set.add(v1)
        set.add(v2)
        assertTrue(set.contains(v1))
        assertTrue(set.contains(v2))
        db.close()
    }

    @Test
    fun `test persistence across database sessions`() {
        val tmpDirectory = createTempDirectory("mapdb_test")
        val dbFile = tmpDirectory.resolve("test.db").toFile()
        val db = DBMaker.fileDB(dbFile).make()
        val map = db.hashMap("test", Serializer.STRING, MapDbValSerializer()).createOrOpen()
        val value = MapVal("a" to ListVal(listOf(NumVal(1), StrVal("test"))))
        map["key"] = value
        db.close()

        val db2 = DBMaker.fileDB(dbFile).make()
        val map2 = db2.hashMap("test", Serializer.STRING, MapDbValSerializer()).createOrOpen()
        assertEquals(value, map2["key"])
        db2.close()
    }
}

