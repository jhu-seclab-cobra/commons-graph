package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.graph.storage.MapDBStorageImpl
import edu.jhu.cobra.commons.graph.storage.MapDbValSerializer
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.StrVal
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

/*
 * Tests for MapDbGraphIOImpl: file validation, export, reserved property keys, and foreign file rejection.
 *
 * - `isValidFile returns false for nonexistent file`
 * - `isValidFile returns false for empty file`
 * - `isValidFile returns true after valid export`
 * - `isValidFile returns false for corrupted file`
 * - `export creates parent directories if not exist`
 * - `export throws when file already exists`
 * - `export with node predicate filters nodes and edges`
 * - `export throws when node property uses reserved key` -- "_nid" would be overwritten
 * - `export throws when edge property uses reserved key` -- "_etag" would be overwritten
 * - `import throws when node record lacks nid key` -- foreign node record is not skipped silently
 * - `import throws when edge record lacks structural keys` -- foreign edge record is not skipped silently
 */
internal class MapDbGraphIOImplTest {
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
}
