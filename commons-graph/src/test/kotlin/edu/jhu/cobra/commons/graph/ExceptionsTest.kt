package edu.jhu.cobra.commons.graph

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Black-box tests for all exception types: message content verification.
 *
 * - `EntityNotExistException message contains id and does not exist` — verifies message format
 * - `EntityNotExistException with Int id contains id` — verifies Int constructor
 * - `EntityAlreadyExistException message contains id and already exists` — verifies message format
 * - `EntityAlreadyExistException with Int id contains id` — verifies Int constructor
 * - `AccessClosedStorageException message contains closed` — verifies message format
 * - `InvalidPropNameException message contains prop name and entity id` — verifies message format
 * - `FrozenLayerModificationException message contains entity id` — verifies message format
 * - `FrozenLayerModificationException with Int id contains id` — verifies Int constructor
 */
internal class ExceptionsTest {

    @Test
    fun `EntityNotExistException message contains id and does not exist`() {
        val ex = EntityNotExistException("missing-node")

        assertTrue(ex.message!!.contains("missing-node"))
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `EntityNotExistException with Int id contains id`() {
        val ex = EntityNotExistException(42)

        assertTrue(ex.message!!.contains("42"))
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `EntityAlreadyExistException message contains id and already exists`() {
        val ex = EntityAlreadyExistException("dup-node")

        assertTrue(ex.message!!.contains("dup-node"))
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test
    fun `EntityAlreadyExistException with Int id contains id`() {
        val ex = EntityAlreadyExistException(7)

        assertTrue(ex.message!!.contains("7"))
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test
    fun `AccessClosedStorageException message contains closed`() {
        val ex = AccessClosedStorageException()

        assertTrue(ex.message!!.contains("closed"))
    }

    @Test
    fun `InvalidPropNameException message contains prop name and entity id`() {
        val ex = InvalidPropNameException("badProp", "node-1")

        assertTrue(ex.message!!.contains("badProp"))
        assertTrue(ex.message!!.contains("node-1"))
    }

    @Test
    fun `FrozenLayerModificationException message contains entity id`() {
        val ex = FrozenLayerModificationException("frozen-node")

        assertTrue(ex.message!!.contains("frozen-node"))
    }

    @Test
    fun `FrozenLayerModificationException with Int id contains id`() {
        val ex = FrozenLayerModificationException(99)

        assertTrue(ex.message!!.contains("99"))
    }
}
