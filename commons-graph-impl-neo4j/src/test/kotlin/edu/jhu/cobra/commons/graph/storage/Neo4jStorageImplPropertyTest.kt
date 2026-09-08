package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * White-box tests for Neo4jStorageImpl: reserved property filtering, null deletes, and meta node persistence.
 *
 * - `getNodeProperties excludes META_ID property`
 * - `getEdgeProperties excludes META_ID property`
 * - `setNodeProperties throws InvalidPropNameException for META_ID`
 * - `setEdgeProperties throws InvalidPropNameException for META_ID`
 * - `setNodeProperties with null removes property from Neo4j`
 * - `setEdgeProperties with null removes property from Neo4j`
 * - `meta operations round-trip`
 * - `setMeta null removes entry`
 * - `meta persists across storage instances` -- meta survives close and reopen via the meta node
 * - `meta node does not appear as graph node` -- meta storage never leaks into nodeIDs
 */
internal class Neo4jStorageImplPropertyTest {
    private lateinit var storage: Neo4jStorageImpl

    private lateinit var graphDir: Path

    @BeforeTest
    fun setUp() {
        graphDir = Files.createTempDirectory("neo4j-wb-test")
        storage = Neo4jStorageImpl(graphDir)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        graphDir.toFile().deleteRecursively()
    }

    // -- META_ID property filtering --

    @Test
    fun `getNodeProperties excludes META_ID property`() {
        val n = storage.addNode(mapOf("visible" to "yes".strVal))
        val props = storage.getNodeProperties(n)
        assertNull(props["__meta_id__"])
        assertEquals(1, props.size)
    }

    @Test
    fun `getEdgeProperties excludes META_ID property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("visible" to "yes".strVal))
        val props = storage.getEdgeProperties(e)
        assertNull(props["__meta_id__"])
        assertEquals(1, props.size)
    }

    // -- InvalidPropNameException --

    @Test
    fun `setNodeProperties throws InvalidPropNameException for META_ID`() {
        val n = storage.addNode()
        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(n, mapOf("__meta_id__" to "value".strVal))
        }
    }

    @Test
    fun `setEdgeProperties throws InvalidPropNameException for META_ID`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertFailsWith<InvalidPropNameException> {
            storage.setEdgeProperties(e, mapOf("__meta_id__" to "value".strVal))
        }
    }

    // -- Null removes property --

    @Test
    fun `setNodeProperties with null removes property from Neo4j`() {
        val n = storage.addNode(mapOf("a" to 1.intVal, "b" to 2.intVal))
        storage.setNodeProperties(n, mapOf("a" to null))
        val props = storage.getNodeProperties(n)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as IntVal).core)
    }

    @Test
    fun `setEdgeProperties with null removes property from Neo4j`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal, "z" to "w".strVal))
        storage.setEdgeProperties(e, mapOf("x" to null))
        val props = storage.getEdgeProperties(e)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    // -- Metadata persisted on the meta node --

    @Test
    fun `meta operations round-trip`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertTrue("version" in storage.metaNames)
    }

    @Test
    fun `setMeta null removes entry`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
        assertFalse("key" in storage.metaNames)
    }

    @Test
    fun `meta persists across storage instances`() {
        storage.setMeta("key", "val".strVal)
        storage.close()
        val reloaded = Neo4jStorageImpl(graphDir)
        assertEquals("val".strVal, reloaded.getMeta("key"))
        assertTrue("key" in reloaded.metaNames)
        reloaded.close()
    }

    @Test
    fun `meta node does not appear as graph node`() {
        storage.setMeta("key", "val".strVal)
        assertTrue(storage.nodeIDs.isEmpty())
    }
}
