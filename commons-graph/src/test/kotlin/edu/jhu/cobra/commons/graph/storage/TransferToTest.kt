package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box tests for the `IStorage.transferTo` contract.
 *
 * Tests:
 * - `transferTo returns map from source node IDs to target node IDs` -- return value semantics
 * - `transferTo preserves node properties in target` -- node property fidelity
 * - `transferTo preserves edge structure with remapped endpoints` -- edge structural fidelity
 * - `transferTo preserves edge properties in target` -- edge property fidelity
 * - `transferTo preserves metadata in target` -- metadata fidelity
 * - `transferTo on empty storage returns empty map` -- empty graph boundary
 * - `transferTo does not modify source storage` -- non-destructive copy
 */
internal class TransferToTest {

    @Test
    fun `transferTo returns map from source node IDs to target node IDs`() {
        val source = NativeStorageImpl()
        val n1 = source.addNode()
        val n2 = source.addNode()
        val n3 = source.addNode()

        val target = NativeStorageImpl()
        val idMap = source.transferTo(target)

        assertEquals(3, idMap.size)
        assertTrue(idMap.containsKey(n1))
        assertTrue(idMap.containsKey(n2))
        assertTrue(idMap.containsKey(n3))
        assertTrue(target.containsNode(idMap[n1]!!))
        assertTrue(target.containsNode(idMap[n2]!!))
        assertTrue(target.containsNode(idMap[n3]!!))
        source.close()
        target.close()
    }

    @Test
    fun `transferTo preserves node properties in target`() {
        val source = NativeStorageImpl()
        source.addNode(mapOf("name" to "A".strVal, "age" to 1.numVal))
        source.addNode(mapOf("name" to "B".strVal))

        val target = NativeStorageImpl()
        source.transferTo(target)

        val allProps = target.nodeIDs.map { target.getNodeProperties(it) }
        val names = allProps.mapNotNull { (it["name"] as? StrVal)?.core }.toSet()
        assertEquals(setOf("A", "B"), names)
        val nodeA = allProps.first { (it["name"] as? StrVal)?.core == "A" }
        assertEquals(1, (nodeA["age"] as NumVal).core)
        source.close()
        target.close()
    }

    @Test
    fun `transferTo preserves edge structure with remapped endpoints`() {
        val source = NativeStorageImpl()
        val n1 = source.addNode()
        val n2 = source.addNode()
        source.addEdge(n1, n2, "rel")

        val target = NativeStorageImpl()
        val idMap = source.transferTo(target)

        assertEquals(1, target.edgeIDs.size)
        val targetEdge = target.edgeIDs.first()
        val structure = target.getEdgeStructure(targetEdge)
        assertEquals(idMap[n1], structure.src)
        assertEquals(idMap[n2], structure.dst)
        assertEquals("rel", structure.tag)
        source.close()
        target.close()
    }

    @Test
    fun `transferTo preserves edge properties in target`() {
        val source = NativeStorageImpl()
        val n1 = source.addNode()
        val n2 = source.addNode()
        source.addEdge(n1, n2, "rel", mapOf("weight" to 1.5.numVal, "label" to "x".strVal))

        val target = NativeStorageImpl()
        source.transferTo(target)

        val edgeProps = target.getEdgeProperties(target.edgeIDs.first())
        assertEquals(1.5, (edgeProps["weight"] as NumVal).core)
        assertEquals("x", (edgeProps["label"] as StrVal).core)
        source.close()
        target.close()
    }

    @Test
    fun `transferTo preserves metadata in target`() {
        val source = NativeStorageImpl()
        source.addNode()
        source.setMeta("version", "1.0".strVal)
        source.setMeta("count", 42.numVal)

        val target = NativeStorageImpl()
        source.transferTo(target)

        assertEquals("1.0", (target.getMeta("version") as StrVal).core)
        assertEquals(42, (target.getMeta("count") as NumVal).core)
        source.close()
        target.close()
    }

    @Test
    fun `transferTo on empty storage returns empty map`() {
        val source = NativeStorageImpl()
        val target = NativeStorageImpl()
        val idMap = source.transferTo(target)

        assertTrue(idMap.isEmpty())
        assertTrue(target.nodeIDs.isEmpty())
        assertTrue(target.edgeIDs.isEmpty())
        source.close()
        target.close()
    }

    @Test
    fun `transferTo does not modify source storage`() {
        val source = NativeStorageImpl()
        val n1 = source.addNode(mapOf("k" to "v".strVal))
        val n2 = source.addNode()
        val e1 = source.addEdge(n1, n2, "rel")

        val target = NativeStorageImpl()
        source.transferTo(target)

        assertTrue(source.containsNode(n1))
        assertTrue(source.containsNode(n2))
        assertTrue(source.containsEdge(e1))
        assertEquals("v", (source.getNodeProperties(n1)["k"] as StrVal).core)
        source.close()
        target.close()
    }
}
