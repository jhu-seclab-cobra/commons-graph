package edu.jhu.cobra.commons.graph

import kotlin.test.*

class ExceptionsTest {
    @Test
    fun `test EntityNotExistException message`() {
        val ex = EntityNotExistException("missing")
        assertTrue(ex.message!!.contains("missing"))
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `test EntityAlreadyExistException message`() {
        val ex = EntityAlreadyExistException("dup")
        assertTrue(ex.message!!.contains("dup"))
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test
    fun `test AccessClosedStorageException message`() {
        val ex = AccessClosedStorageException()
        assertTrue(ex.message!!.contains("closed"))
    }

    @Test
    fun `test FrozenLayerModificationException message`() {
        val ex = FrozenLayerModificationException("frozen_node")
        assertTrue(ex.message!!.contains("frozen_node"))
    }

    @Test
    fun `test EntityNotExistException with InternalID`() {
        val ex = EntityNotExistException("some-edge-id")
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `test EntityAlreadyExistException with InternalID`() {
        val ex = EntityAlreadyExistException("some-edge-id")
        assertTrue(ex.message!!.contains("already exists"))
    }
}
