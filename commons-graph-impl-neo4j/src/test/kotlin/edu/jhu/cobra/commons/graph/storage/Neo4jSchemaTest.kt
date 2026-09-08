package edu.jhu.cobra.commons.graph.storage

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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Tests for Neo4jSchema: reserved schema names, the meta node, and storage ID decoding.
 *
 * - `findMetaNode returns null before any meta node exists` -- absent meta node
 * - `findOrCreateMetaNode creates one meta node and reuses it` -- idempotent creation
 * - `meta node carries META_LABEL and not NODE_LABEL` -- meta node stays out of entity nodes
 * - `storageId decodes the Long SID property as Int` -- SID decoding
 */
internal class Neo4jSchemaTest {
    private lateinit var tempDir: Path
    private lateinit var managementService: DatabaseManagementService
    private lateinit var database: GraphDatabaseService

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("neo4j-schema-test")
        managementService = DatabaseManagementServiceBuilder(tempDir).build()
        database = managementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME)
    }

    @AfterTest
    fun tearDown() {
        managementService.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `findMetaNode returns null before any meta node exists`() {
        database.beginTx().use { tx ->
            assertNull(tx.findMetaNode())
        }
    }

    @Test
    fun `findOrCreateMetaNode creates one meta node and reuses it`() {
        database.beginTx().use { tx ->
            val created = tx.findOrCreateMetaNode()
            val again = tx.findOrCreateMetaNode()

            assertEquals(created.elementId, again.elementId)
            assertEquals(0L, created.getProperty(META_ID))
            assertEquals(created.elementId, tx.findMetaNode()?.elementId)
            tx.commit()
        }
        database.beginTx().use { tx ->
            assertEquals(1, tx.findNodes(META_LABEL).stream().count())
        }
    }

    @Test
    fun `meta node carries META_LABEL and not NODE_LABEL`() {
        database.beginTx().use { tx ->
            val meta = tx.findOrCreateMetaNode()

            assertTrue(meta.hasLabel(META_LABEL))
            assertFalse(meta.hasLabel(NODE_LABEL))
        }
    }

    @Test
    fun `storageId decodes the Long SID property as Int`() {
        database.beginTx().use { tx ->
            val node = tx.createNode(NODE_LABEL)
            node.setProperty(SID, 42L)
            val edge = node.createRelationshipTo(node, EDGE_TYPE)
            edge.setProperty(SID, 7L)

            assertEquals(42, node.storageId)
            assertEquals(7, edge.storageId)
        }
    }
}
