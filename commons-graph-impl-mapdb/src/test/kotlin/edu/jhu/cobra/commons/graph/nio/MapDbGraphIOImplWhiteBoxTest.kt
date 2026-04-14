/**
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
 * - `export with node predicate filters nodes and edges`
 * - `import with predicate filters imported entities`
 * - `export and import empty storage`
 * - `node properties preserved through serialization`
 * - `edge properties and type preserved through serialization`
 */
package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.storage.MapDBStorageImpl
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
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
        srcStorage.addNode(mapOf("name" to "Node1".strVal, "count" to 10.numVal))
        srcStorage.addNode(mapOf("name" to "Node2".strVal))

        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        val dstNodes = dstStorage.nodeIDs.toList()
        assertEquals(2, dstNodes.size)
        val allProps = dstNodes.map { dstStorage.getNodeProperties(it) }
        assertTrue(allProps.any { (it["name"] as? StrVal)?.core == "Node1" && (it["count"] as? NumVal)?.core == 10 })
        assertTrue(allProps.any { (it["name"] as? StrVal)?.core == "Node2" })
        dstStorage.close()
    }

    @Test
    fun `export then import preserves edges with properties`() {
        val n1 = srcStorage.addNode()
        val n2 = srcStorage.addNode()
        srcStorage.addEdge(n1, n2, "connects", mapOf("weight" to 5.numVal))

        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(1, dstStorage.edgeIDs.size)
        val importedEdge = dstStorage.edgeIDs.first()
        assertEquals(5, (dstStorage.getEdgeProperties(importedEdge)["weight"] as NumVal).core)
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
        srcStorage.addEdge(n1, n2, "e", mapOf("weight" to 10.numVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(2, dstStorage.nodeIDs.size)
        assertEquals(1, dstStorage.edgeIDs.size)
        val importedEdge = dstStorage.edgeIDs.first()
        assertEquals(10, (dstStorage.getEdgeProperties(importedEdge)["weight"] as NumVal).core)
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
}
