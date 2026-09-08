package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/*
 * White-box tests for Neo4jStorageImpl: entity existence guards.
 *
 * - `addEdge missing src throws EntityNotExistException`
 * - `addEdge missing dst throws EntityNotExistException`
 * - `deleteNode nonexistent throws EntityNotExistException`
 * - `deleteEdge nonexistent throws EntityNotExistException`
 * - `getNodeProperties nonexistent throws EntityNotExistException`
 * - `getIncomingEdges nonexistent throws EntityNotExistException`
 * - `getOutgoingEdges nonexistent throws EntityNotExistException`
 */
internal class Neo4jStorageImplGuardTest {
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

    // -- Entity existence contracts --

    @Test
    fun `addEdge missing src throws EntityNotExistException`() {
        val dst = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(-1, dst, "e") }
    }

    @Test
    fun `addEdge missing dst throws EntityNotExistException`() {
        val src = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(src, -1, "e") }
    }

    @Test
    fun `deleteNode nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }

    @Test
    fun `deleteEdge nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(-1) }
    }

    @Test
    fun `getNodeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(-1) }
    }

    @Test
    fun `getIncomingEdges nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(-1) }
    }

    @Test
    fun `getOutgoingEdges nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(-1) }
    }
}
