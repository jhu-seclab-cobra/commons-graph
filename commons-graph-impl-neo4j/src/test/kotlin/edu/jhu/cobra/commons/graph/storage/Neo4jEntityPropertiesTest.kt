package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.dbms.api.DatabaseManagementService
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.graphdb.GraphDatabaseService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/*
 * Tests for Neo4jEntityProperties: IValue property access on Neo4j entities with reserved-name guards.
 *
 * - `set then get round-trips an IValue` -- serialization round trip
 * - `get returns null for an absent property` -- absent name
 * - `keys excludes reserved schema properties` -- reserved filtering
 * - `set with reserved name throws InvalidPropNameException` -- write guard
 * - `get with reserved name throws InvalidPropNameException` -- read guard
 * - `propertyEntries snapshots all user properties` -- map snapshot
 */
internal class Neo4jEntityPropertiesTest {
    private lateinit var tempDir: Path
    private lateinit var managementService: DatabaseManagementService
    private lateinit var database: GraphDatabaseService

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("neo4j-props-test")
        managementService = DatabaseManagementServiceBuilder(tempDir).build()
        database = managementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME)
    }

    @AfterTest
    fun tearDown() {
        managementService.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `set then get round-trips an IValue`() {
        database.beginTx().use { tx ->
            val node = tx.createNode(NODE_LABEL)

            node["name"] = "a".strVal
            node["count"] = 3.intVal

            assertEquals("a".strVal, node["name"])
            assertEquals(3.intVal, node["count"])
        }
    }

    @Test
    fun `get returns null for an absent property`() {
        database.beginTx().use { tx ->
            val node = tx.createNode(NODE_LABEL)

            assertNull(node["missing"])
        }
    }

    @Test
    fun `keys excludes reserved schema properties`() {
        database.beginTx().use { tx ->
            val node = tx.createNode(NODE_LABEL)
            node.setProperty(SID, 1L)
            node.setProperty(TAG, "t")
            node.setProperty(META_ID, 0L)
            node["user"] = 1.intVal

            assertEquals(listOf("user"), node.keys.toList())
        }
    }

    @Test
    fun `set with reserved name throws InvalidPropNameException`() {
        database.beginTx().use { tx ->
            val node = tx.createNode(NODE_LABEL)

            assertFailsWith<InvalidPropNameException> { node[SID] = 1.intVal }
            assertFailsWith<InvalidPropNameException> { node[TAG] = 1.intVal }
            assertFailsWith<InvalidPropNameException> { node[META_ID] = 1.intVal }
        }
    }

    @Test
    fun `get with reserved name throws InvalidPropNameException`() {
        database.beginTx().use { tx ->
            val node = tx.createNode(NODE_LABEL)
            node.setProperty(SID, 1L)

            assertFailsWith<InvalidPropNameException> { node[SID] }
        }
    }

    @Test
    fun `propertyEntries snapshots all user properties`() {
        database.beginTx().use { tx ->
            val node = tx.createNode(NODE_LABEL)
            node.setProperty(SID, 1L)
            node["a"] = 1.intVal
            node["b"] = "x".strVal

            assertEquals(mapOf("a" to 1.intVal, "b" to "x".strVal), node.propertyEntries())
        }
    }
}
