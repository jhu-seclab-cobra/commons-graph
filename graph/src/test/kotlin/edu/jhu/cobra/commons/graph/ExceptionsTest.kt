package edu.jhu.cobra.commons.graph

import kotlin.test.*

class ExceptionsTest {
    @Test
    fun `test EntityNotExistException message`() {
        val id = NodeID("missing")
        val ex = EntityNotExistException(id)
        assertTrue(ex.message!!.contains("missing"))
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `test EntityAlreadyExistException message`() {
        val id = NodeID("dup")
        val ex = EntityAlreadyExistException(id)
        assertTrue(ex.message!!.contains("dup"))
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test
    fun `test InvalidPropNameException message`() {
        val id = NodeID("node1")
        val ex = InvalidPropNameException("meta_bad", id)
        assertTrue(ex.message!!.contains("meta_bad"))
        assertTrue(ex.message!!.contains("node1"))
    }

    @Test
    fun `test InvalidPropNameException with null id`() {
        val ex = InvalidPropNameException("meta_bad", null)
        assertTrue(ex.message!!.contains("meta_bad"))
        assertTrue(ex.message!!.contains("null"))
    }

    @Test
    fun `test AccessClosedStorageException message`() {
        val ex = AccessClosedStorageException()
        assertTrue(ex.message!!.contains("closed"))
    }

    @Test
    fun `test FrozenLayerModificationException message`() {
        val id = NodeID("frozen_node")
        val ex = FrozenLayerModificationException(id)
        assertTrue(ex.message!!.contains("frozen_node"))
    }

    @Test
    fun `test StorageFrozenException message`() {
        val ex = StorageFrozenException()
        assertTrue(ex.message!!.contains("frozen"))
    }

    @Test
    fun `test EntityNotExistException with EdgeID`() {
        val eid = EdgeID(NodeID("a"), NodeID("b"), "rel")
        val ex = EntityNotExistException(eid)
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `test EntityAlreadyExistException with EdgeID`() {
        val eid = EdgeID(NodeID("a"), NodeID("b"), "rel")
        val ex = EntityAlreadyExistException(eid)
        assertTrue(ex.message!!.contains("already exists"))
    }
}
