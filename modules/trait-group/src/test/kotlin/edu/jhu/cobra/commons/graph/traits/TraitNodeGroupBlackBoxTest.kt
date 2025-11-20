package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.*
import kotlin.test.*

/**
 * Black-box tests for TraitNodeGroup interface.
 * Tests focus on public API behavior, inputs, outputs, and exceptions.
 */
class TraitNodeGroupBlackBoxTest : AbcTraitNodeGroupTest() {
    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // ========== addGroupNode(group: String, suffix: String? = null): N ==========

    @Test
    fun `test addGroupNode with group name adds node with auto-generated suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users")

        assertNotNull(node)
        assertEquals("Test@users#1", node.id.name)
        assertTrue(graph.containNode(node))
        assertEquals(1, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test addGroupNode with group name increments counter`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode("users")

        assertEquals("Test@users#1", node1.id.name)
        assertEquals("Test@users#2", node2.id.name)
        assertEquals(2, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test addGroupNode with custom suffix uses provided suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")

        assertNotNull(node)
        assertEquals("Test@users#custom", node.id.name)
        assertTrue(graph.containNode(node))
    }

    @Test
    fun `test addGroupNode with custom suffix still increments counter`() {
        registerGroup("users")
        graph.addGroupNode("users", "custom")
        val node2 = graph.addGroupNode("users")

        assertEquals("Test@users#custom", graph.getGroupNode("users", "custom")?.id?.name)
        assertEquals("Test@users#2", node2.id.name)
        assertEquals(2, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test addGroupNode with null suffix uses auto-generated suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", null)

        assertNotNull(node)
        assertEquals("Test@users#1", node.id.name)
    }

    @Test
    fun `test addGroupNode when group not registered throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users")
        }
    }

    @Test
    fun `test addGroupNode with empty group name throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("")
        }
    }

    @Test
    fun `test addGroupNode with group name containing @ throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("user@group")
        }
    }

    @Test
    fun `test addGroupNode with group name containing # throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("user#group")
        }
    }

    @Test
    fun `test addGroupNode with empty suffix throws IllegalArgumentException`() {
        registerGroup("users")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users", "")
        }
    }

    @Test
    fun `test addGroupNode with suffix containing # throws IllegalArgumentException`() {
        registerGroup("users")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users", "suf#fix")
        }
    }

    @Test
    fun `test addGroupNode with duplicate node ID throws EntityAlreadyExistException`() {
        registerGroup("users")
        graph.addGroupNode("users", "custom")
        assertFailsWith<EntityAlreadyExistException> {
            graph.addGroupNode("users", "custom")
        }
    }

    @Test
    fun `test addGroupNode with same suffix different groups creates different nodes`() {
        registerGroup("users")
        registerGroup("products")
        val userNode = graph.addGroupNode("users", "item1")
        val productNode = graph.addGroupNode("products", "item1")

        assertNotNull(userNode)
        assertNotNull(productNode)
        assertEquals("Test@users#item1", userNode.id.name)
        assertEquals("Test@products#item1", productNode.id.name)
    }

    // ========== addGroupNode(sameGroupNode: AbcNode, suffix: String? = null): N ==========

    @Test
    fun `test addGroupNode with sameGroupNode extracts group name and adds node`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode(node1)

        assertNotNull(node2)
        assertEquals("Test@users#2", node2.id.name)
        assertTrue(graph.containNode(node2))
    }

    @Test
    fun `test addGroupNode with sameGroupNode and custom suffix uses provided suffix`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode(node1, "custom")

        assertNotNull(node2)
        assertEquals("Test@users#custom", node2.id.name)
        assertTrue(graph.containNode(node2))
    }

    @Test
    fun `test addGroupNode with sameGroupNode and null suffix uses auto-generated suffix`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "node1")
        val node2 = graph.addGroupNode(node1, null)

        assertNotNull(node2)
        assertEquals("Test@users#2", node2.id.name)
    }

    @Test
    fun `test addGroupNode with sameGroupNode when node ID format invalid throws IllegalArgumentException`() {
        val invalidNode = graph.addNode(NodeID("invalid-format"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(invalidNode)
        }
    }

    @Test
    fun `test addGroupNode with sameGroupNode when node ID missing @ throws IllegalArgumentException`() {
        val node = graph.addNode(NodeID("no-at-symbol"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode with sameGroupNode when node ID missing # throws IllegalArgumentException`() {
        val node = graph.addNode(NodeID("Test@group-only"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode with sameGroupNode when group name contains @ throws IllegalArgumentException`() {
        val node = graph.addNode(NodeID("Test@user@group#suffix"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode with sameGroupNode when group name contains # throws IllegalArgumentException`() {
        val node = graph.addNode(NodeID("Test@user#group#suffix"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode with sameGroupNode when group not registered throws IllegalArgumentException`() {
        val node = graph.addNode(NodeID("Test@users#1"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode with sameGroupNode with invalid suffix throws IllegalArgumentException`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node1, "")
        }
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node1, "suf#fix")
        }
    }

    @Test
    fun `test addGroupNode with sameGroupNode with duplicate node ID throws EntityAlreadyExistException`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "custom")
        assertFailsWith<EntityAlreadyExistException> {
            graph.addGroupNode(node1, "custom")
        }
    }

    // ========== getGroupNode(group: String, suffix: String): N? ==========

    @Test
    fun `test getGroupNode returns node when exists`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "node1")
        val retrieved = graph.getGroupNode("users", "node1")

        assertNotNull(retrieved)
        assertEquals(node1.id, retrieved.id)
    }

    @Test
    fun `test getGroupNode returns null when node does not exist`() {
        registerGroup("users")
        val retrieved = graph.getGroupNode("users", "nonexistent")
        assertNull(retrieved)
    }

    @Test
    fun `test getGroupNode with empty group name throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("", "suffix")
        }
    }

    @Test
    fun `test getGroupNode with group name containing @ throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("user@group", "suffix")
        }
    }

    @Test
    fun `test getGroupNode with group name containing # throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("user#group", "suffix")
        }
    }

    @Test
    fun `test getGroupNode with empty suffix throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("users", "")
        }
    }

    @Test
    fun `test getGroupNode with suffix containing # throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("users", "suf#fix")
        }
    }

    @Test
    fun `test getGroupNode with numeric suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "123")
        val retrieved = graph.getGroupNode("users", "123")

        assertNotNull(retrieved)
        assertEquals(node.id, retrieved.id)
    }

    @Test
    fun `test getGroupNode with special characters in suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "node-123_abc")
        val retrieved = graph.getGroupNode("users", "node-123_abc")

        assertNotNull(retrieved)
        assertEquals(node.id, retrieved.id)
    }

    // ========== getGroupName(node: AbcNode): String? ==========

    @Test
    fun `test getGroupName returns group name for valid format`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "node1")
        val groupName = graph.getGroupName(node1)

        assertNotNull(groupName)
        assertEquals("users", groupName)
    }

    @Test
    fun `test getGroupName returns null when node ID missing @`() {
        val node = graph.addNode(NodeID("no-at-symbol"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName)
    }

    @Test
    fun `test getGroupName returns null when node ID missing #`() {
        val node = graph.addNode(NodeID("Test@group-only"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName)
    }

    @Test
    fun `test getGroupName returns null when @ at start`() {
        val node = graph.addNode(NodeID("@group#suffix"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName)
    }

    @Test
    fun `test getGroupName returns null when # before @`() {
        val node = graph.addNode(NodeID("Test#group@suffix"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName)
    }

    @Test
    fun `test getGroupName returns null when empty group name`() {
        val node = graph.addNode(NodeID("Test@#suffix"))
        val groupName = graph.getGroupName(node)
        assertNull(groupName)
    }

    @Test
    fun `test getGroupName returns group name when # at end`() {
        val node = graph.addNode(NodeID("Test@group#"))
        val groupName = graph.getGroupName(node)
        assertNotNull(groupName)
        assertEquals("group", groupName)
    }

    @Test
    fun `test getGroupName with Unicode characters`() {
        registerGroup("用户")
        val node = graph.addGroupNode("用户", "节点1")
        val groupName = graph.getGroupName(node)

        assertNotNull(groupName)
        assertEquals("用户", groupName)
    }

    @Test
    fun `test getGroupName with complex group name`() {
        registerGroup("user-group_123")
        val node = graph.addGroupNode("user-group_123")
        val groupName = graph.getGroupName(node)

        assertNotNull(groupName)
        assertEquals("user-group_123", groupName)
    }

    @Test
    fun `test getGroupName with root node format`() {
        val rootNode = graph.addNode(NodeID("Test@users#0"))
        val groupName = graph.getGroupName(rootNode)

        assertNotNull(groupName)
        assertEquals("users", groupName)
    }

    // ========== Counter Behavior ==========

    @Test
    fun `test counter monotonically increases`() {
        registerGroup("users")
        graph.addGroupNode("users")
        assertEquals(1, graph.groupedNodesCounter["users"])

        val node2 = graph.addGroupNode("users")
        assertEquals(2, graph.groupedNodesCounter["users"])

        graph.addGroupNode("users")
        assertEquals(3, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter never decreases after node deletion`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode("users")
        assertEquals(2, graph.groupedNodesCounter["users"])

        graph.delNode(node1)
        graph.delNode(node2)

        val node3 = graph.addGroupNode("users")
        assertEquals("Test@users#3", node3.id.name)
        assertEquals(3, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter persists after all nodes deleted`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode("users")
        assertEquals(2, graph.groupedNodesCounter["users"])

        graph.delNode(node1)
        graph.delNode(node2)

        val node3 = graph.addGroupNode("users")
        assertEquals("Test@users#3", node3.id.name)
        assertEquals(3, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter increments by one for each auto-generated suffix`() {
        registerGroup("users")
        repeat(5) {
            graph.addGroupNode("users")
        }
        assertEquals(5, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter starts at registered value`() {
        registerGroup("users")
        graph.groupedNodesCounter["users"] = 5
        val node = graph.addGroupNode("users")

        assertEquals("Test@users#6", node.id.name)
        assertEquals(6, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter with multiple groups`() {
        registerGroup("users")
        registerGroup("products")
        val user1 = graph.addGroupNode("users")
        val user2 = graph.addGroupNode("users")
        val product1 = graph.addGroupNode("products")

        assertEquals("Test@users#1", user1.id.name)
        assertEquals("Test@users#2", user2.id.name)
        assertEquals("Test@products#1", product1.id.name)

        assertEquals(2, graph.groupedNodesCounter["users"])
        assertEquals(1, graph.groupedNodesCounter["products"])
    }

    @Test
    fun `test counter with large numbers`() {
        registerGroup("users")
        repeat(100) {
            graph.addGroupNode("users")
        }
        assertEquals(100, graph.groupedNodesCounter["users"])

        val node = graph.addGroupNode("users")
        assertEquals("Test@users#101", node.id.name)
        assertEquals(101, graph.groupedNodesCounter["users"])
    }

    // ========== Node ID Format ==========

    @Test
    fun `test node ID format follows graphName@group#suffix pattern`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")

        assertEquals("Test@users#custom", node.id.name)
    }

    @Test
    fun `test node ID format with auto-generated suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users")

        assertEquals("Test@users#1", node.id.name)
    }

    // ========== Edge Cases ==========

    @Test
    fun `test addGroupNode with very long group name`() {
        val longGroupName = "a".repeat(1000)
        registerGroup(longGroupName)
        val node = graph.addGroupNode(longGroupName)

        assertNotNull(node)
        assertEquals("Test@$longGroupName#1", node.id.name)
    }

    @Test
    fun `test addGroupNode with very long suffix`() {
        val longSuffix = "s".repeat(1000)
        registerGroup("users")
        val node = graph.addGroupNode("users", longSuffix)

        assertNotNull(node)
        assertEquals("Test@users#$longSuffix", node.id.name)
    }

    @Test
    fun `test addGroupNode with whitespace in group name`() {
        registerGroup("user group")
        val node = graph.addGroupNode("user group")

        assertNotNull(node)
        assertEquals("Test@user group#1", node.id.name)
    }

    @Test
    fun `test addGroupNode with whitespace in suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "node 1")

        assertNotNull(node)
        assertEquals("Test@users#node 1", node.id.name)
    }

    @Test
    fun `test addGroupNode with Unicode characters`() {
        registerGroup("用户")
        val node = graph.addGroupNode("用户", "节点1")

        assertNotNull(node)
        assertEquals("Test@用户#节点1", node.id.name)
    }

    @Test
    fun `test addGroupNode numeric suffix conflicts with auto-generated`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        assertEquals("Test@users#1", node1.id.name)

        val node2 = graph.addGroupNode("users", "2")
        assertEquals("Test@users#2", node2.id.name)

        val node3 = graph.addGroupNode("users")
        assertEquals("Test@users#3", node3.id.name)
    }

    @Test
    fun `test addGroupNode same suffix after deletion`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "custom")
        graph.delNode(node1)
        val node2 = graph.addGroupNode("users", "custom")

        assertNotNull(node2)
        assertEquals("Test@users#custom", node2.id.name)
    }

    @Test
    fun `test groupedNodesCounter registration required before adding nodes`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users")
        }
        assertFalse(graph.groupedNodesCounter.containsKey("users"))

        registerGroup("users")
        assertTrue(graph.groupedNodesCounter.containsKey("users"))
        assertEquals(0, graph.groupedNodesCounter["users"])
    }
}
