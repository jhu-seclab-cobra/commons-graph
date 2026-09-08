/*
 * White-box tests for MapDB graph IO round-trip via [MapDbGraphIOImpl].
 *
 * - `isValidFile returns false for nonexistent file`
 * - `isValidFile returns false for empty file`
 * - `isValidFile returns true after valid export`
 * - `isValidFile returns false for corrupted file`
 * - `export then import preserves nodes with properties`
 * - `export then import preserves edges with properties`
 * - `export creates parent directories if not exist`
 * - `export throws when file already exists`
 * - `import throws when file does not exist`
 * - `import adds nodes with properties`
 * - `import adds edges with properties`
 * - `import creates src and dst nodes for edges`
 * - `import throws when edge references missing node` — no silent reattachment to raw IDs
 * - `export with node predicate filters nodes and edges`
 * - `import with predicate filters imported entities`
 * - `export and import empty storage`
 * - `node properties preserved through serialization`
 * - `edge properties and type preserved through serialization`
 * - `export then import preserves storage meta` — meta round-trip per design-storage.md
 * - `import skips edges whose endpoint was filtered out` — endpoint filter, no error on skipped node
 * - `export throws when node property uses reserved key` — "_nid" would be overwritten
 * - `export throws when edge property uses reserved key` — "_etag" would be overwritten
 * - `import throws when node record lacks nid key` — foreign node record is not skipped silently
 * - `import throws when edge record lacks structural keys` — foreign edge record is not skipped silently
 */
package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.graph.storage.MapDBStorageImpl
import edu.jhu.cobra.commons.graph.storage.MapDbValSerializer
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.mapVal
import edu.jhu.cobra.commons.value.strVal
import org.mapdb.DBMaker
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MapDbGraphIOImplWhiteBoxTest {
    private lateinit var srcStorage: MapDBStorageImpl
    private lateinit var tempFile: Path

    @BeforeTest
    fun setUp() {
        srcStorage = MapDBStorageImpl { memoryDB() }
        tempFile = Files.createTempDirectory("mapdb-io-test").resolve("test.mapdb")
    }

    @AfterTest
    fun tearDown() {
        srcStorage.close()
        tempFile.deleteIfExists()
        tempFile.parent?.toFile()?.deleteRecursively()
    }

    // -- isValidFile --

    @Test
    fun `isValidFile returns false for nonexistent file`() {
        assertFalse(MapDbGraphIOImpl.isValidFile(Paths.get("/tmp/nonexistent_${System.nanoTime()}.mapdb")))
    }

    @Test
    fun `isValidFile returns false for empty file`() {
        val emptyFile = Files.createTempFile("mapdb-empty", ".mapdb")
        assertFalse(MapDbGraphIOImpl.isValidFile(emptyFile))
        emptyFile.deleteIfExists()
    }

    @Test
    fun `isValidFile returns true after valid export`() {
        srcStorage.addNode()
        MapDbGraphIOImpl.export(tempFile, srcStorage)
        assertTrue(MapDbGraphIOImpl.isValidFile(tempFile))
    }

    @Test
    fun `isValidFile returns false for corrupted file`() {
        val badFile = Files.createTempFile("mapdb-bad", ".mapdb")
        badFile.toFile().writeText("not a mapdb file")
        assertFalse(MapDbGraphIOImpl.isValidFile(badFile))
        badFile.deleteIfExists()
    }

    // -- Export/Import round-trip --

    @Test
    fun `export then import preserves nodes with properties`() {
        srcStorage.addNode(mapOf("name" to "Node1".strVal, "count" to 10.intVal))
        srcStorage.addNode(mapOf("name" to "Node2".strVal))

        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        val dstNodes = dstStorage.nodeIDs.toList()
        assertEquals(2, dstNodes.size)
        val allProps = dstNodes.map { dstStorage.getNodeProperties(it) }
        assertTrue(allProps.any { (it["name"] as? StrVal)?.core == "Node1" && (it["count"] as? IntVal)?.core == 10L })
        assertTrue(allProps.any { (it["name"] as? StrVal)?.core == "Node2" })
        dstStorage.close()
    }

    @Test
    fun `export then import preserves edges with properties`() {
        val n1 = srcStorage.addNode()
        val n2 = srcStorage.addNode()
        srcStorage.addEdge(n1, n2, "connects", mapOf("weight" to 5.intVal))

        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(1, dstStorage.edgeIDs.size)
        val importedEdge = dstStorage.edgeIDs.first()
        assertEquals(5, (dstStorage.getEdgeProperties(importedEdge)["weight"] as IntVal).core)
        assertEquals("connects", dstStorage.getEdgeStructure(importedEdge).tag)
        dstStorage.close()
    }

    // -- Export creates parent directories --

    @Test
    fun `export creates parent directories if not exist`() {
        val nestedFile = Files.createTempDirectory("mapdb-nest").resolve("a/b/c/test.mapdb")
        srcStorage.addNode()
        val result = MapDbGraphIOImpl.export(nestedFile, srcStorage)
        assertEquals(nestedFile, result)
        assertTrue(MapDbGraphIOImpl.isValidFile(nestedFile))
        nestedFile.parent?.toFile()?.deleteRecursively()
    }

    // -- Export precondition --

    @Test
    fun `export throws when file already exists`() {
        srcStorage.addNode()
        MapDbGraphIOImpl.export(tempFile, srcStorage)
        assertFailsWith<IllegalArgumentException> {
            MapDbGraphIOImpl.export(tempFile, srcStorage)
        }
    }

    // -- Import precondition --

    @Test
    fun `import throws when file does not exist`() {
        val badPath = Paths.get("/tmp/nonexistent_${System.nanoTime()}.mapdb")
        val dstStorage = MapDBStorageImpl { memoryDB() }
        assertFailsWith<IllegalArgumentException> {
            MapDbGraphIOImpl.import(badPath, dstStorage)
        }
        dstStorage.close()
    }

    // -- Import adds entities --

    @Test
    fun `import adds nodes with properties`() {
        srcStorage.addNode(mapOf("version" to "v2".strVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(1, dstStorage.nodeIDs.size)
        val importedNode = dstStorage.nodeIDs.first()
        assertEquals("v2", (dstStorage.getNodeProperties(importedNode)["version"] as StrVal).core)
        dstStorage.close()
    }

    @Test
    fun `import adds edges with properties`() {
        val n1 = srcStorage.addNode()
        val n2 = srcStorage.addNode()
        srcStorage.addEdge(n1, n2, "e", mapOf("weight" to 10.intVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(2, dstStorage.nodeIDs.size)
        assertEquals(1, dstStorage.edgeIDs.size)
        val importedEdge = dstStorage.edgeIDs.first()
        assertEquals(10, (dstStorage.getEdgeProperties(importedEdge)["weight"] as IntVal).core)
        dstStorage.close()
    }

    @Test
    fun `import creates src and dst nodes for edges`() {
        val n1 = srcStorage.addNode()
        val n2 = srcStorage.addNode()
        srcStorage.addEdge(n1, n2, "e")
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(2, dstStorage.nodeIDs.size)
        assertEquals(1, dstStorage.edgeIDs.size)
        dstStorage.close()
    }

    @Test
    fun `import throws when edge references missing node`() {
        val db = DBMaker.fileDB(tempFile.toFile()).make()
        val serializer = MapDbValSerializer<MapVal>()
        db.indexTreeList("nodes", serializer).create().add(
            mapOf<String, IValue>("_nid" to IntVal(5)).mapVal,
        )
        db.indexTreeList("edges", serializer).create().add(
            mapOf<String, IValue>(
                "_esrc" to IntVal(5),
                "_edst" to IntVal(7),
                "_etag" to StrVal("e"),
            ).mapVal,
        )
        db.close()

        val dstStorage = MapDBStorageImpl { memoryDB() }
        repeat(8) { dstStorage.addNode() }
        try {
            assertFailsWith<IllegalStateException> {
                MapDbGraphIOImpl.import(tempFile, dstStorage)
            }
        } finally {
            dstStorage.close()
        }
    }

    // -- Export with predicate filtering --

    @Test
    fun `export with node predicate filters nodes and edges`() {
        val n1 = srcStorage.addNode(mapOf("tag" to "keep".strVal))
        val n2 = srcStorage.addNode(mapOf("tag" to "drop".strVal))
        val n3 = srcStorage.addNode(mapOf("tag" to "keep".strVal))
        val e1 = srcStorage.addEdge(n1, n3, "ok")
        srcStorage.addEdge(n1, n2, "bad")

        val keepNodeIds = setOf(n1, n3)
        val keepEdgeIds = setOf(e1)
        MapDbGraphIOImpl.export(tempFile, srcStorage) { entity ->
            entity in keepNodeIds || entity in keepEdgeIds
        }

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(2, dstStorage.nodeIDs.size)
        assertEquals(1, dstStorage.edgeIDs.size)
        dstStorage.close()
    }

    // -- Import with predicate filtering --

    @Test
    fun `import with predicate filters imported entities`() {
        val n1 = srcStorage.addNode(mapOf("type" to "a".strVal))
        srcStorage.addNode(mapOf("type" to "b".strVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage) { entity -> entity == n1 }

        assertEquals(1, dstStorage.nodeIDs.size)
        dstStorage.close()
    }

    // -- Empty storage round-trip --

    @Test
    fun `export and import empty storage`() {
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(0, dstStorage.nodeIDs.size)
        assertEquals(0, dstStorage.edgeIDs.size)
        dstStorage.close()
    }

    // -- Serialization round-trip --

    @Test
    fun `node properties preserved through serialization`() {
        srcStorage.addNode(mapOf("data" to "test".strVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(1, dstStorage.nodeIDs.size)
        val importedNode = dstStorage.nodeIDs.first()
        assertEquals("test", (dstStorage.getNodeProperties(importedNode)["data"] as StrVal).core)
        dstStorage.close()
    }

    @Test
    fun `edge properties and type preserved through serialization`() {
        val n1 = srcStorage.addNode()
        val n2 = srcStorage.addNode()
        srcStorage.addEdge(n1, n2, "type-with-special_chars", mapOf("data" to "test".strVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(1, dstStorage.edgeIDs.size)
        val importedEdge = dstStorage.edgeIDs.first()
        assertEquals("test", (dstStorage.getEdgeProperties(importedEdge)["data"] as StrVal).core)
        assertEquals("type-with-special_chars", dstStorage.getEdgeStructure(importedEdge).tag)
        dstStorage.close()
    }

    // -- Meta round-trip (design-storage.md: meta has the same lifetime as entity data) --

    @Test
    fun `export then import preserves storage meta`() {
        srcStorage.setMeta("version", "1.2.3".strVal)
        srcStorage.setMeta("count", 42.intVal)
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(setOf("version", "count"), dstStorage.metaNames)
        assertEquals("1.2.3", (dstStorage.getMeta("version") as StrVal).core)
        assertEquals(42, (dstStorage.getMeta("count") as IntVal).core.toInt())
        dstStorage.close()
    }

    // -- Reserved property keys --

    @Test
    fun `export throws when node property uses reserved key`() {
        srcStorage.addNode(mapOf("_nid" to "boom".strVal))
        assertFailsWith<InvalidPropNameException> {
            MapDbGraphIOImpl.export(tempFile, srcStorage)
        }
    }

    @Test
    fun `export throws when edge property uses reserved key`() {
        val n1 = srcStorage.addNode()
        val n2 = srcStorage.addNode()
        srcStorage.addEdge(n1, n2, "rel", mapOf("_etag" to "boom".strVal))
        assertFailsWith<InvalidPropNameException> {
            MapDbGraphIOImpl.export(tempFile, srcStorage)
        }
    }

    // -- Foreign file rejection --

    @Test
    fun `import throws when node record lacks nid key`() {
        val db = DBMaker.fileDB(tempFile.toFile()).make()
        val serializer = MapDbValSerializer<MapVal>()
        db.indexTreeList("nodes", serializer).create().add(
            mapOf<String, IValue>("name" to StrVal("orphan")).mapVal,
        )
        db.indexTreeList("edges", serializer).create()
        db.close()

        val dstStorage = MapDBStorageImpl { memoryDB() }
        try {
            assertFailsWith<IllegalStateException> {
                MapDbGraphIOImpl.import(tempFile, dstStorage)
            }
        } finally {
            dstStorage.close()
        }
    }

    @Test
    fun `import throws when edge record lacks structural keys`() {
        val db = DBMaker.fileDB(tempFile.toFile()).make()
        val serializer = MapDbValSerializer<MapVal>()
        db.indexTreeList("nodes", serializer).create().add(
            mapOf<String, IValue>("_nid" to IntVal(1)).mapVal,
        )
        db.indexTreeList("edges", serializer).create().add(
            mapOf<String, IValue>("_esrc" to IntVal(1), "_edst" to IntVal(1)).mapVal,
        )
        db.close()

        val dstStorage = MapDBStorageImpl { memoryDB() }
        try {
            assertFailsWith<IllegalStateException> {
                MapDbGraphIOImpl.import(tempFile, dstStorage)
            }
        } finally {
            dstStorage.close()
        }
    }

    // -- Import predicate on original IDs; edges follow their endpoints --

    @Test
    fun `import skips edges whose endpoint was filtered out`() {
        val n1 = srcStorage.addNode(mapOf("type" to "a".strVal))
        val n2 = srcStorage.addNode(mapOf("type" to "b".strVal))
        srcStorage.addEdge(n1, n2, "link")
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage) { entity -> entity == n1 }

        assertEquals(1, dstStorage.nodeIDs.size)
        assertEquals(0, dstStorage.edgeIDs.size)
        dstStorage.close()
    }
}
