package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.*
import kotlin.test.*

class TraitNodeGroupTest : AbcTraitNodeGroupTest() {

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // region addGroupNode(group, suffix)

    @Test
    fun `test addGroupNode_groupName_addsNodeWithAutoSuffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users")

        assertEquals("Test@users#1", node.id.name)
        assertTrue(graph.containNode(node.id))
        assertEquals(1, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test addGroupNode_groupName_incrementsCounter`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode("users")

        assertEquals("Test@users#1", node1.id.name)
        assertEquals("Test@users#2", node2.id.name)
        assertEquals(2, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test addGroupNode_customSuffix_usesProvidedSuffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")

        assertEquals("Test@users#custom", node.id.name)
        assertTrue(graph.containNode(node.id))
    }

    @Test
    fun `test addGroupNode_customSuffix_stillIncrementsCounter`() {
        registerGroup("users")
        graph.addGroupNode("users", "custom")
        val node2 = graph.addGroupNode("users")

        assertEquals("Test@users#2", node2.id.name)
        assertEquals(2, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test addGroupNode_nullSuffix_usesAutoSuffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", null)

        assertEquals("Test@users#1", node.id.name)
    }

    @Test
    fun `test addGroupNode_unregisteredGroup_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users")
        }
    }

    @Test
    fun `test addGroupNode_emptyGroupName_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("")
        }
    }

    @Test
    fun `test addGroupNode_groupNameContainingAt_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("user@group")
        }
    }

    @Test
    fun `test addGroupNode_groupNameContainingHash_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("user#group")
        }
    }

    @Test
    fun `test addGroupNode_emptySuffix_throwsIllegalArgument`() {
        registerGroup("users")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users", "")
        }
    }

    @Test
    fun `test addGroupNode_suffixContainingHash_throwsIllegalArgument`() {
        registerGroup("users")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users", "suf#fix")
        }
    }

    @Test
    fun `test addGroupNode_duplicateNodeID_throwsEntityAlreadyExist`() {
        registerGroup("users")
        graph.addGroupNode("users", "custom")
        assertFailsWith<EntityAlreadyExistException> {
            graph.addGroupNode("users", "custom")
        }
    }

    @Test
    fun `test addGroupNode_sameSuffixDifferentGroups_createsDifferentNodes`() {
        registerGroup("users")
        registerGroup("products")
        val userNode = graph.addGroupNode("users", "item1")
        val productNode = graph.addGroupNode("products", "item1")

        assertEquals("Test@users#item1", userNode.id.name)
        assertEquals("Test@products#item1", productNode.id.name)
    }

    // endregion

    // region addGroupNode(sameGroupNode, suffix)

    @Test
    fun `test addGroupNode_sameGroupNode_extractsGroupAndAddsNode`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode(node1)

        assertEquals("Test@users#2", node2.id.name)
        assertTrue(graph.containNode(node2.id))
    }

    @Test
    fun `test addGroupNode_sameGroupNodeCustomSuffix_usesProvidedSuffix`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode(node1, "custom")

        assertEquals("Test@users#custom", node2.id.name)
    }

    @Test
    fun `test addGroupNode_sameGroupNodeNullSuffix_usesAutoSuffix`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "node1")
        val node2 = graph.addGroupNode(node1, null)

        assertEquals("Test@users#2", node2.id.name)
    }

    @Test
    fun `test addGroupNode_invalidNodeFormat_throwsIllegalArgument`() {
        val invalidNode = graph.addNode(NodeID("invalid-format"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(invalidNode)
        }
    }

    @Test
    fun `test addGroupNode_nodeIDMissingAt_throwsIllegalArgument`() {
        val node = graph.addNode(NodeID("no-at-symbol"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode_nodeIDMissingHash_throwsIllegalArgument`() {
        val node = graph.addNode(NodeID("Test@group-only"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode_nodeGroupContainingAt_throwsIllegalArgument`() {
        val node = graph.addNode(NodeID("Test@user@group#suffix"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode_nodeGroupContainingHash_throwsIllegalArgument`() {
        val node = graph.addNode(NodeID("Test@user#group#suffix"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode_sameGroupNodeUnregistered_throwsIllegalArgument`() {
        val node = graph.addNode(NodeID("Test@users#1"))
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `test addGroupNode_sameGroupNodeInvalidSuffix_throwsIllegalArgument`() {
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
    fun `test addGroupNode_sameGroupNodeDuplicate_throwsEntityAlreadyExist`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "custom")
        assertFailsWith<EntityAlreadyExistException> {
            graph.addGroupNode(node1, "custom")
        }
    }

    // endregion

    // region getGroupNode

    @Test
    fun `test getGroupNode_existing_returnsNode`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "node1")

        val retrieved = graph.getGroupNode("users", "node1")

        assertNotNull(retrieved)
        assertEquals(node.id, retrieved.id)
    }

    @Test
    fun `test getGroupNode_nonExistent_returnsNull`() {
        registerGroup("users")

        assertNull(graph.getGroupNode("users", "nonexistent"))
    }

    @Test
    fun `test getGroupNode_emptyGroupName_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("", "suffix")
        }
    }

    @Test
    fun `test getGroupNode_groupNameContainingAt_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("user@group", "suffix")
        }
    }

    @Test
    fun `test getGroupNode_groupNameContainingHash_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("user#group", "suffix")
        }
    }

    @Test
    fun `test getGroupNode_emptySuffix_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("users", "")
        }
    }

    @Test
    fun `test getGroupNode_suffixContainingHash_throwsIllegalArgument`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("users", "suf#fix")
        }
    }

    @Test
    fun `test getGroupNode_numericSuffix_returnsNode`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "123")

        val retrieved = graph.getGroupNode("users", "123")

        assertNotNull(retrieved)
        assertEquals(node.id, retrieved.id)
    }

    @Test
    fun `test getGroupNode_specialCharacterSuffix_returnsNode`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "node-123_abc")

        val retrieved = graph.getGroupNode("users", "node-123_abc")

        assertNotNull(retrieved)
        assertEquals(node.id, retrieved.id)
    }

    // endregion

    // region getGroupName

    @Test
    fun `test getGroupName_validFormat_returnsGroupName`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "node1")

        assertEquals("users", graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_missingAt_returnsNull`() {
        val node = graph.addNode(NodeID("no-at-symbol"))

        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_missingHash_returnsNull`() {
        val node = graph.addNode(NodeID("Test@group-only"))

        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_atAtStart_returnsNull`() {
        val node = graph.addNode(NodeID("@group#suffix"))

        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_hashBeforeAt_returnsNull`() {
        val node = graph.addNode(NodeID("Test#group@suffix"))

        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_emptyGroupName_returnsNull`() {
        val node = graph.addNode(NodeID("Test@#suffix"))

        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_hashAtEnd_returnsGroupName`() {
        val node = graph.addNode(NodeID("Test@group#"))

        assertEquals("group", graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_unicodeCharacters_returnsGroupName`() {
        registerGroup("用户")
        val node = graph.addGroupNode("用户", "节点1")

        assertEquals("用户", graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_complexGroupName_returnsGroupName`() {
        registerGroup("user-group_123")
        val node = graph.addGroupNode("user-group_123")

        assertEquals("user-group_123", graph.getGroupName(node))
    }

    @Test
    fun `test getGroupName_singleCharGroup_returnsGroupName`() {
        val node = graph.addNode(NodeID("Test@a#suffix"))

        assertEquals("a", graph.getGroupName(node))
    }

    // endregion

    // region Counter behavior

    @Test
    fun `test counter_monotonicallyIncreases`() {
        registerGroup("users")

        graph.addGroupNode("users")
        assertEquals(1, graph.groupedNodesCounter["users"])

        graph.addGroupNode("users")
        assertEquals(2, graph.groupedNodesCounter["users"])

        graph.addGroupNode("users")
        assertEquals(3, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter_neverDecreasesAfterDeletion`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode("users")
        graph.delNode(node1.id)
        graph.delNode(node2.id)

        val node3 = graph.addGroupNode("users")

        assertEquals("Test@users#3", node3.id.name)
        assertEquals(3, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter_incrementsByOneForEachAutoSuffix`() {
        registerGroup("users")
        repeat(5) { graph.addGroupNode("users") }

        assertEquals(5, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter_startsAtRegisteredValue`() {
        registerGroup("users")
        graph.groupedNodesCounter["users"] = 5

        val node = graph.addGroupNode("users")

        assertEquals("Test@users#6", node.id.name)
        assertEquals(6, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter_multipleGroupsIndependent`() {
        registerGroup("users")
        registerGroup("products")
        graph.addGroupNode("users")
        graph.addGroupNode("users")
        graph.addGroupNode("products")

        assertEquals(2, graph.groupedNodesCounter["users"])
        assertEquals(1, graph.groupedNodesCounter["products"])
    }

    @Test
    fun `test counter_largeNumbers`() {
        registerGroup("users")
        repeat(100) { graph.addGroupNode("users") }

        val node = graph.addGroupNode("users")

        assertEquals("Test@users#101", node.id.name)
        assertEquals(101, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter_customSuffixStillIncrements`() {
        registerGroup("users")
        graph.addGroupNode("users", "custom")

        assertEquals(1, graph.groupedNodesCounter["users"])

        graph.addGroupNode("users")
        assertEquals(2, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter_startsAtZero`() {
        registerGroup("users")

        assertEquals(0, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `test counter_registrationRequiredBeforeAdding`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users")
        }
        assertFalse(graph.groupedNodesCounter.containsKey("users"))

        registerGroup("users")
        assertTrue(graph.groupedNodesCounter.containsKey("users"))
        assertEquals(0, graph.groupedNodesCounter["users"])
    }

    // endregion

    // region Node ID format

    @Test
    fun `test nodeIDFormat_followsGraphNameAtGroupHashSuffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")

        assertEquals("Test@users#custom", node.id.name)
    }

    @Test
    fun `test nodeIDFormat_autoSuffix_usesCounterValue`() {
        registerGroup("users")
        val node = graph.addGroupNode("users")

        assertEquals("Test@users#1", node.id.name)
    }

    @Test
    fun `test nodeIDFormat_structure_threePartsCorrect`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")
        val parts = node.id.name.split("@", "#")

        assertEquals(3, parts.size)
        assertEquals("Test", parts[0])
        assertEquals("users", parts[1])
        assertEquals("custom", parts[2])
    }

    // endregion

    // region Edge cases

    @Test
    fun `test addGroupNode_veryLongGroupName_accepted`() {
        val longGroupName = "a".repeat(1000)
        registerGroup(longGroupName)
        val node = graph.addGroupNode(longGroupName)

        assertEquals("Test@$longGroupName#1", node.id.name)
    }

    @Test
    fun `test addGroupNode_veryLongSuffix_accepted`() {
        val longSuffix = "s".repeat(1000)
        registerGroup("users")
        val node = graph.addGroupNode("users", longSuffix)

        assertEquals("Test@users#$longSuffix", node.id.name)
    }

    @Test
    fun `test addGroupNode_whitespaceInGroupName_accepted`() {
        registerGroup("user group")
        val node = graph.addGroupNode("user group")

        assertEquals("Test@user group#1", node.id.name)
    }

    @Test
    fun `test addGroupNode_whitespaceInSuffix_accepted`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "node 1")

        assertEquals("Test@users#node 1", node.id.name)
    }

    @Test
    fun `test addGroupNode_unicodeCharacters_accepted`() {
        registerGroup("用户")
        val node = graph.addGroupNode("用户", "节点1")

        assertEquals("Test@用户#节点1", node.id.name)
    }

    @Test
    fun `test addGroupNode_numericSuffixConflictsWithAuto_handledCorrectly`() {
        registerGroup("users")
        graph.addGroupNode("users")
        graph.addGroupNode("users", "2")
        val node3 = graph.addGroupNode("users")

        assertEquals("Test@users#3", node3.id.name)
    }

    @Test
    fun `test addGroupNode_sameSuffixAfterDeletion_succeeds`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users", "custom")
        graph.delNode(node1.id)
        val node2 = graph.addGroupNode("users", "custom")

        assertEquals("Test@users#custom", node2.id.name)
    }

    // endregion
}
