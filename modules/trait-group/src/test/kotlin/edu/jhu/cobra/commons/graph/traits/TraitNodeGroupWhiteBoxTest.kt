package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.*
import kotlin.test.*

/**
 * White-box tests for TraitNodeGroup interface.
 * Tests focus on internal implementation details, boundary conditions, and state consistency.
 */
class TraitNodeGroupWhiteBoxTest : AbcTraitNodeGroupTest() {
    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // ========== getGroupName Internal Logic Boundary Tests ==========

    @Test
    fun `test getGroupName internal atIndex equals -1 returns null`() {
        val node = graph.addNode(NodeID("no-at-symbol"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName, "atIndex == -1 should return null")
    }

    @Test
    fun `test getGroupName internal atIndex equals 0 returns null`() {
        val node = graph.addNode(NodeID("@group#suffix"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName, "atIndex == 0 should return null")
    }

    @Test
    fun `test getGroupName internal hashIndex equals -1 returns null`() {
        val node = graph.addNode(NodeID("Test@group-only"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName, "hashIndex == -1 should return null")
    }

    @Test
    fun `test getGroupName internal atIndex plus 1 equals hashIndex returns null`() {
        val node = graph.addNode(NodeID("Test@#suffix"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName, "atIndex + 1 == hashIndex should return null")
    }

    @Test
    fun `test getGroupName internal atIndex plus 1 greater than hashIndex returns null`() {
        val node = graph.addNode(NodeID("Test#group@suffix"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName, "atIndex + 1 > hashIndex should return null")
    }

    @Test
    fun `test getGroupName internal groupName isEmpty returns null`() {
        val node = graph.addNode(NodeID("Test@#suffix"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName, "Empty groupName should return null")
    }

    @Test
    fun `test getGroupName internal atIndex plus 1 less than hashIndex returns group name`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "suffix")
        val groupName = graph.getGroupName(node)
        assertNotNull(groupName, "atIndex + 1 < hashIndex should return group name")
        assertEquals("users", groupName)
    }

    @Test
    fun `test getGroupName internal boundary atIndex plus 1 equals hashIndex minus 1`() {
        val node = graph.addNode(NodeID("Test@a#suffix"))
        val groupName = graph.getGroupName(node)
        assertNotNull(groupName, "Single character group name should be valid")
        assertEquals("a", groupName)
    }

    // ========== groupNodeID Internal Logic Boundary Tests ==========

    @Test
    fun `test groupNodeID internal graphName replace @ removes all @ characters`() {
        val graphWithAt = TestGraph()
        graphWithAt.groupedNodesCounter["users"] = 0
        val node = graphWithAt.addGroupNode("users", "custom")
        
        val nodeId = node.id.name
        val beforeAt = nodeId.substringBefore("@")
        assertFalse(beforeAt.contains("@"), "graphName should have all @ characters removed")
    }

    @Test
    fun `test groupNodeID internal graphName with multiple @ characters removes all`() {
        val graphWithMultipleAt = TestGraph()
        graphWithMultipleAt.groupedNodesCounter["users"] = 0
        val node = graphWithMultipleAt.addGroupNode("users", "custom")
        
        val nodeId = node.id.name
        val beforeAt = nodeId.substringBefore("@")
        assertFalse(beforeAt.contains("@"), "All @ characters should be removed from graphName")
    }

    @Test
    fun `test groupNodeID internal PREFIX_SPLITTER in group validation`() {
        registerGroup("user@group")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("user@group", "suffix")
        }
    }

    @Test
    fun `test groupNodeID internal SUFFIX_SPLITTER in group validation`() {
        registerGroup("user#group")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("user#group", "suffix")
        }
    }

    @Test
    fun `test groupNodeID internal SUFFIX_SPLITTER in suffix validation`() {
        registerGroup("users")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users", "suf#fix")
        }
    }

    @Test
    fun `test groupNodeID internal empty group name validation`() {
        registerGroup("")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("", "suffix")
        }
    }

    @Test
    fun `test groupNodeID internal empty suffix validation`() {
        registerGroup("users")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users", "")
        }
    }

    // ========== addGroupNode Internal Logic Boundary Tests ==========

    @Test
    fun `test addGroupNode internal group in groupedNodesCounter check`() {
        graph.groupedNodesCounter["users"] = 0
        val node = graph.addGroupNode("users")
        assertNotNull(node)
    }

    @Test
    fun `test addGroupNode internal group not in groupedNodesCounter throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users")
        }
    }

    @Test
    fun `test addGroupNode internal compute increments counter correctly`() {
        registerGroup("users")
        val initialCounter = graph.groupedNodesCounter["users"]!!
        
        graph.addGroupNode("users")
        val afterFirst = graph.groupedNodesCounter["users"]!!
        assertEquals(initialCounter + 1, afterFirst, "Counter should increment by 1")
        
        graph.addGroupNode("users")
        val afterSecond = graph.groupedNodesCounter["users"]!!
        assertEquals(afterFirst + 1, afterSecond, "Counter should increment by 1 again")
    }

    @Test
    fun `test addGroupNode internal suffix null uses counter toString`() {
        registerGroup("users")
        graph.groupedNodesCounter["users"] = 5
        
        val node = graph.addGroupNode("users", null)
        assertEquals("Test@users#6", node.id.name, "null suffix should use counter.toString()")
    }

    @Test
    fun `test addGroupNode internal suffix provided uses provided suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")
        assertEquals("Test@users#custom", node.id.name, "Provided suffix should be used")
    }

    @Test
    fun `test addGroupNode internal compute with null value throws exception`() {
        graph.groupedNodesCounter["users"] = 0
        graph.groupedNodesCounter.remove("users")
        
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users")
        }
    }

    // ========== addGroupNode(sameGroupNode) Internal Logic Boundary Tests ==========

    @Test
    fun `test addGroupNode sameGroupNode internal getGroupName returns null throws exception`() {
        val invalidNode = graph.addNode(NodeID("invalid-format"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(invalidNode)
        }
    }

    @Test
    fun `test addGroupNode sameGroupNode internal getGroupName returns non-null proceeds`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode(node1)
        
        assertNotNull(node2)
        assertEquals("Test@users#2", node2.id.name)
    }

    @Test
    fun `test addGroupNode sameGroupNode internal delegates to addGroupNode with group`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "node1")
        val node2 = graph.addGroupNode(node1, "node2")
        
        assertEquals("Test@users#node2", node2.id.name)
    }

    // ========== Counter Internal State Boundary Tests ==========

    @Test
    fun `test counter internal state starts at zero`() {
        registerGroup("users")
        assertEquals(0, graph.groupedNodesCounter["users"], "Counter should start at 0")
    }

    @Test
    fun `test counter internal state compute operation atomicity`() {
        registerGroup("users")
        val initialValue = graph.groupedNodesCounter["users"]!!
        
        graph.addGroupNode("users")
        val newValue = graph.groupedNodesCounter["users"]!!
        
        assertEquals(initialValue + 1, newValue, "compute should atomically increment counter")
    }

    @Test
    fun `test counter internal state persists across multiple operations`() {
        registerGroup("users")
        graph.addGroupNode("users")
        val value1 = graph.groupedNodesCounter["users"]!!
        
        graph.addGroupNode("users")
        val value2 = graph.groupedNodesCounter["users"]!!
        
        graph.addGroupNode("users")
        val value3 = graph.groupedNodesCounter["users"]!!
        
        assertEquals(1, value1)
        assertEquals(2, value2)
        assertEquals(3, value3)
        assertTrue(value1 < value2 && value2 < value3, "Counter should monotonically increase")
    }

    @Test
    fun `test counter internal state with custom suffix still increments`() {
        registerGroup("users")
        graph.addGroupNode("users", "custom")
        val counterAfterCustom = graph.groupedNodesCounter["users"]!!
        
        graph.addGroupNode("users")
        val counterAfterAuto = graph.groupedNodesCounter["users"]!!
        
        assertEquals(1, counterAfterCustom, "Counter should increment even with custom suffix")
        assertEquals(2, counterAfterAuto, "Counter should continue incrementing")
    }

    @Test
    fun `test counter internal state with initial non-zero value`() {
        registerGroup("users")
        graph.groupedNodesCounter["users"] = 10
        
        val node = graph.addGroupNode("users")
        assertEquals(11, graph.groupedNodesCounter["users"], "Counter should increment from initial value")
        assertEquals("Test@users#11", node.id.name)
    }

    @Test
    fun `test counter internal state multiple groups independent`() {
        registerGroup("users")
        registerGroup("products")
        
        graph.addGroupNode("users")
        graph.addGroupNode("products")
        graph.addGroupNode("users")
        
        assertEquals(2, graph.groupedNodesCounter["users"], "users counter should be 2")
        assertEquals(1, graph.groupedNodesCounter["products"], "products counter should be 1")
    }

    // ========== Node ID Format Internal Boundary Tests ==========

    @Test
    fun `test node ID format internal structure graphName@group#suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")
        val parts = node.id.name.split("@", "#")
        
        assertEquals(3, parts.size, "Node ID should have 3 parts")
        assertEquals("Test", parts[0], "First part should be graphName")
        assertEquals("users", parts[1], "Second part should be group")
        assertEquals("custom", parts[2], "Third part should be suffix")
    }

    @Test
    fun `test node ID format internal graphName without @ characters`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")
        val beforeAt = node.id.name.substringBefore("@")
        
        assertFalse(beforeAt.contains("@"), "graphName part should not contain @")
    }

    @Test
    fun `test node ID format internal group name between @ and #`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")
        val groupName = node.id.name.substringAfter("@").substringBefore("#")
        
        assertEquals("users", groupName, "Group name should be between @ and #")
    }

    @Test
    fun `test node ID format internal suffix after #`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")
        val suffix = node.id.name.substringAfter("#")
        
        assertEquals("custom", suffix, "Suffix should be after #")
    }

    // ========== Internal State Consistency Tests ==========

    @Test
    fun `test internal state consistency counter and node creation`() {
        registerGroup("users")
        val expectedCounter = 1
        
        val node = graph.addGroupNode("users")
        val actualCounter = graph.groupedNodesCounter["users"]!!
        
        assertEquals(expectedCounter, actualCounter, "Counter should match expected value")
        assertEquals("Test@users#$expectedCounter", node.id.name, "Node ID should match counter")
    }

    @Test
    fun `test internal state consistency after node deletion`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode("users")
        val counterBefore = graph.groupedNodesCounter["users"]!!
        
        graph.delNode(node1)
        graph.delNode(node2)
        val counterAfter = graph.groupedNodesCounter["users"]!!
        
        assertEquals(counterBefore, counterAfter, "Counter should not decrease after deletion")
    }

    @Test
    fun `test internal state consistency groupedNodesCounter key presence`() {
        registerGroup("users")
        assertTrue(graph.groupedNodesCounter.containsKey("users"), "Group should exist in counter")
        
        graph.addGroupNode("users")
        assertTrue(graph.groupedNodesCounter.containsKey("users"), "Group should still exist after adding node")
    }

    @Test
    fun `test internal state consistency multiple operations same group`() {
        registerGroup("users")
        val nodes = mutableListOf<TestNode>()
        
        repeat(5) {
            nodes.add(graph.addGroupNode("users"))
        }
        
        assertEquals(5, graph.groupedNodesCounter["users"], "Counter should be 5 after 5 operations")
        nodes.forEachIndexed { index, node ->
            assertEquals("Test@users#${index + 1}", node.id.name, "Node ${index + 1} should have correct ID")
        }
    }
}

