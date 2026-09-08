package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.graph.storage.StorageFixtures
import edu.jhu.cobra.commons.value.BoolVal
import edu.jhu.cobra.commons.value.FloatVal
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.floatVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for NativeCsvIOImpl: export/import round-trip fidelity.
 *
 * - `round-trip preserves node count` -- structural fidelity
 * - `round-trip preserves node properties` -- node property fidelity
 * - `round-trip preserves edge count and structure` -- edge structural fidelity
 * - `round-trip preserves edges and their tags` -- edge tag fidelity
 * - `round-trip preserves metadata` -- metadata fidelity
 * - `round-trip preserves mixed property types` -- type fidelity across StrVal, IntVal, FloatVal, BoolVal
 * - `round-trip preserves special characters in property values` -- CSV escaping
 * - `round-trip preserves property value ending in backslash` -- escaped trailing backslash
 *   must not hide the following delimiter
 * - `round-trip on empty storage produces empty target` -- empty boundary
 * - `multiple round-trips produce identical data` -- idempotent serialization
 * - `null property values round-trip as absent` -- null serialized as empty, deserialized as absent
 */
internal class NativeCsvIOImplRoundTripTest {
    private lateinit var tempDir: Path

    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("csv-io-test")
        storage = NativeStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        if (tempDir.exists()) {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun roundTrip(source: IStorage): NativeStorageImpl {
        val exportPath = tempDir.resolve("export_${System.nanoTime()}")
        NativeCsvIOImpl.export(exportPath, source)
        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)
        return target
    }

    @Test
    fun `round-trip preserves node count`() {
        storage.addNode()
        storage.addNode()
        storage.addNode()

        val target = roundTrip(storage)

        assertEquals(3, target.nodeIDs.size)
    }

    @Test
    fun `round-trip preserves node properties`() {
        storage.addNode(mapOf("name" to "Alice".strVal, "age" to 30.intVal))
        storage.addNode(mapOf("name" to "Bob".strVal))

        val target = roundTrip(storage)

        val allProps = target.nodeIDs.map { target.getNodeProperties(it) }
        val names = allProps.mapNotNull { (it["name"] as? StrVal)?.core }.toSet()
        assertEquals(setOf("Alice", "Bob"), names)
        val alice = allProps.first { (it["name"] as? StrVal)?.core == "Alice" }
        assertEquals(30L, (alice["age"] as IntVal).core)
    }

    @Test
    fun `round-trip preserves edge count and structure`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        storage.addEdge(n1, n2, StorageFixtures.EDGE_TAG_1)
        storage.addEdge(n2, n3, StorageFixtures.EDGE_TAG_2)
        storage.addEdge(n1, n3, StorageFixtures.EDGE_TAG_3)

        val target = roundTrip(storage)

        assertEquals(3, target.nodeIDs.size)
        assertEquals(3, target.edgeIDs.size)
    }

    @Test
    fun `round-trip preserves edges and their tags`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        storage.addEdge(n1, n2, StorageFixtures.EDGE_TAG_1)
        storage.addEdge(n2, n3, StorageFixtures.EDGE_TAG_2)

        val target = roundTrip(storage)

        assertEquals(3, target.nodeIDs.size)
        assertEquals(2, target.edgeIDs.size)
        val tags = target.edgeIDs.map { target.getEdgeStructure(it).tag }.toSet()
        assertEquals(setOf(StorageFixtures.EDGE_TAG_1, StorageFixtures.EDGE_TAG_2), tags)
    }

    @Test
    fun `round-trip preserves metadata`() {
        storage.addNode()
        storage.setMeta("version", "2.0".strVal)
        storage.setMeta("count", 42.intVal)

        val target = roundTrip(storage)

        assertEquals("2.0", (target.getMeta("version") as StrVal).core)
        assertEquals(42L, (target.getMeta("count") as IntVal).core)
    }

    @Test
    fun `round-trip preserves mixed property types`() {
        storage.addNode(
            mapOf(
                "name" to "Node1".strVal,
                "age" to 25.intVal,
                "weight" to 1.5.floatVal,
                "active" to true.boolVal,
            ),
        )

        val target = roundTrip(storage)

        val props = target.getNodeProperties(target.nodeIDs.first())
        assertEquals("Node1", (props["name"] as StrVal).core)
        assertEquals(25L, (props["age"] as IntVal).core)
        assertEquals(1.5, (props["weight"] as FloatVal).core)
        assertEquals(true, (props["active"] as BoolVal).core)
    }

    @Test
    fun `round-trip preserves special characters in property values`() {
        storage.addNode(
            mapOf(
                "commas" to "a,b,c".strVal,
                "newline" to "line1\nline2".strVal,
                "backslash" to "C:\\path\\file".strVal,
            ),
        )

        val target = roundTrip(storage)

        val props = target.getNodeProperties(target.nodeIDs.first())
        assertEquals("a,b,c", (props["commas"] as StrVal).core)
        assertEquals("line1\nline2", (props["newline"] as StrVal).core)
        assertEquals("C:\\path\\file", (props["backslash"] as StrVal).core)
    }

    @Test
    fun `round-trip preserves property value ending in backslash`() {
        // First node pins "trailing" as the first property column, so the
        // second node's backslash-terminated cell is followed by a delimiter.
        storage.addNode(mapOf("trailing" to "plain".strVal))
        val nodeId =
            storage.addNode(
                mapOf(
                    "trailing" to "tail\\".strVal,
                    "next" to "value".strVal,
                ),
            )

        val target = roundTrip(storage)

        val props = target.getNodeProperties(nodeId)
        assertEquals("tail\\", (props["trailing"] as StrVal).core)
        assertEquals("value", (props["next"] as StrVal).core)
    }

    @Test
    fun `round-trip on empty storage produces empty target`() {
        val target = roundTrip(storage)

        assertTrue(target.nodeIDs.isEmpty())
        assertTrue(target.edgeIDs.isEmpty())
    }

    @Test
    fun `multiple round-trips produce identical data`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        storage.addEdge(n1, n2, "rel")
        storage.setMeta("v", "1".strVal)

        var current: IStorage = storage
        repeat(3) {
            current = roundTrip(current)
        }

        assertEquals(2, current.nodeIDs.size)
        assertEquals(1, current.edgeIDs.size)
        val names =
            current.nodeIDs
                .map { current.getNodeProperties(it) }
                .mapNotNull { (it["name"] as? StrVal)?.core }
                .toSet()
        assertEquals(setOf("A", "B"), names)
        assertEquals("1", (current.getMeta("v") as StrVal).core)
    }

    // ========================================================================
    // Null / empty property handling
    // ========================================================================
    @Test
    fun `null property values round-trip as absent`() {
        storage.addNode(mapOf("present" to "yes".strVal))
        storage.addNode()

        val target = roundTrip(storage)

        val allProps = target.nodeIDs.map { target.getNodeProperties(it) }
        val withProp = allProps.first { it.containsKey("present") }
        val withoutProp = allProps.first { !it.containsKey("present") }
        assertEquals("yes", (withProp["present"] as StrVal).core)
        assertNull(withoutProp["present"])
    }
}
