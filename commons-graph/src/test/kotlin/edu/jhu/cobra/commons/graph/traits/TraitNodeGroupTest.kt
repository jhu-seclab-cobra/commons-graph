package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-box tests for TraitNodeGroup: property-based group membership,
 * assignGroup, addGroupNode, getGroupNode, getGroupName, getGroupSuffix,
 * getGroupNodes, counter persistence, rebuildGroupCaches.
 *
 * - `addGroupNode stores group property on node` — verifies group assignment
 * - `addGroupNode stores suffix property on node` — verifies suffix assignment
 * - `addGroupNode auto suffix uses counter` — verifies auto-increment suffix
 * - `addGroupNode custom suffix uses provided value` — verifies explicit suffix
 * - `addGroupNode increments counter` — verifies monotonic counter
 * - `addGroupNode custom suffix still increments counter` — verifies counter always advances
 * - `addGroupNode unregistered group throws IllegalArgumentException` — verifies registration guard
 * - `addGroupNode empty group throws IllegalArgumentException` — verifies empty group guard
 * - `addGroupNode empty suffix throws IllegalArgumentException` — verifies empty suffix guard
 * - `addGroupNode duplicate suffix same group throws EntityAlreadyExistException` — verifies uniqueness
 * - `addGroupNode same suffix different groups creates different nodes` — verifies group isolation
 * - `addGroupNode group name with special characters allowed` — verifies no char restrictions
 * - `addGroupNode from sameGroupNode extracts group` — verifies group extraction
 * - `addGroupNode from sameGroupNode custom suffix` — verifies suffix override
 * - `addGroupNode from node without group throws IllegalArgumentException` — verifies guard
 * - `assignGroup sets properties on existing node` — verifies property-based assignment
 * - `assignGroup unregistered group throws IllegalArgumentException` — verifies guard
 * - `getGroupNode existing returns node` — verifies retrieval
 * - `getGroupNode nonexistent returns null` — verifies absent case
 * - `getGroupNode empty group throws IllegalArgumentException` — verifies guard
 * - `getGroupNode empty suffix throws IllegalArgumentException` — verifies guard
 * - `getGroupName returns group from property` — verifies property read
 * - `getGroupName node without group returns null` — verifies absent case
 * - `getGroupSuffix returns suffix from property` — verifies property read
 * - `getGroupNodes returns all nodes in group` — verifies enumeration
 * - `getGroupNodes empty group returns empty` — verifies empty case
 * - `counter persisted to meta survives rebuild` — verifies persistence
 * - `suffixIndex restored after rebuild` — verifies cache rebuild
 * - `addGroupNode after rebuild works` — verifies counter restoration
 * - `counter never decreases after deletion` — verifies monotonicity
 * - `counter multiple groups are independent` — verifies group isolation
 * - `node id does not encode group information` — verifies decoupled ID
 * - `rebuildGroupCaches on empty graph clears caches` — verifies empty rebuild
 * - `getGroupNode returns null for nonexistent suffix` — verifies suffix miss
 * - `assignGroup explicit suffix stores provided suffix` — suffix != null path
 * - `assignGroup null suffix uses auto counter` — suffix == null path
 * - `addGroupNode first call initializes global counter from zero` — META_GLOBAL_COUNTER absent
 * - `addGroupNode second call reads existing global counter` — META_GLOBAL_COUNTER present
 * - `rebuildGroupCaches skips node with group but no suffix` — PROP_GROUP set, PROP_SUFFIX absent
 * - `rebuildGroupCaches skips node without group or suffix` — neither property set
 * - `rebuildGroupCaches persisted counter zero` — meta counter absent for group
 * - `rebuildGroupCaches persisted counter positive` — meta counter exists for group
 * - `getGroupNodes returns nodes for populated group` — group has nodes
 * - `getGroupNodes returns empty for group with no matching nodes` — group has no nodes
 */
internal class TraitNodeGroupTest : AbcTraitNodeGroupTest() {

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // region addGroupNode

    @Test
    fun `addGroupNode stores group property on node`() {
        registerGroup("users")
        val node = graph.addGroupNode("users")
        assertEquals("users", graph.getGroupName(node))
    }

    @Test
    fun `addGroupNode stores suffix property on node`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "admin")
        assertEquals("admin", graph.getGroupSuffix(node))
    }

    @Test
    fun `addGroupNode auto suffix uses counter`() {
        registerGroup("users")
        val node = graph.addGroupNode("users")
        assertEquals("1", graph.getGroupSuffix(node))
    }

    @Test
    fun `addGroupNode custom suffix uses provided value`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "root")
        assertEquals("root", graph.getGroupSuffix(node))
    }

    @Test
    fun `addGroupNode increments counter`() {
        registerGroup("users")
        graph.addGroupNode("users")
        graph.addGroupNode("users")
        assertEquals(2, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `addGroupNode custom suffix still increments counter`() {
        registerGroup("users")
        graph.addGroupNode("users", "custom")
        val auto = graph.addGroupNode("users")
        assertEquals("2", graph.getGroupSuffix(auto))
    }

    @Test
    fun `addGroupNode unregistered group throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("unregistered")
        }
    }

    @Test
    fun `addGroupNode empty group throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("")
        }
    }

    @Test
    fun `addGroupNode empty suffix throws IllegalArgumentException`() {
        registerGroup("users")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users", "")
        }
    }

    @Test
    fun `addGroupNode duplicate suffix same group throws EntityAlreadyExistException`() {
        registerGroup("users")
        graph.addGroupNode("users", "dup")
        assertFailsWith<EntityAlreadyExistException> {
            graph.addGroupNode("users", "dup")
        }
    }

    @Test
    fun `addGroupNode same suffix different groups creates different nodes`() {
        registerGroup("users")
        registerGroup("products")
        val user = graph.addGroupNode("users", "item1")
        val product = graph.addGroupNode("products", "item1")
        assertNotEquals(user.id, product.id)
        assertEquals("users", graph.getGroupName(user))
        assertEquals("products", graph.getGroupName(product))
    }

    @Test
    fun `addGroupNode group name with special characters allowed`() {
        val group = "/src/Controller@2.php#main"
        graph.groupedNodesCounter[group] = 0
        val node = graph.addGroupNode(group)
        assertEquals(group, graph.getGroupName(node))
    }

    // endregion

    // region addGroupNode(sameGroupNode)

    @Test
    fun `addGroupNode from sameGroupNode extracts group`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode(node1)
        assertEquals("users", graph.getGroupName(node2))
    }

    @Test
    fun `addGroupNode from sameGroupNode custom suffix`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")
        val node2 = graph.addGroupNode(node1, "custom")
        assertEquals("custom", graph.getGroupSuffix(node2))
    }

    @Test
    fun `addGroupNode from node without group throws IllegalArgumentException`() {
        val node = graph.addNode("plain-node")
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    // endregion

    // region assignGroup

    @Test
    fun `assignGroup sets properties on existing node`() {
        registerGroup("files")
        val node = graph.addNode("my-custom-id")
        graph.assignGroup(node, "files", "root")
        assertEquals("files", graph.getGroupName(node))
        assertEquals("root", graph.getGroupSuffix(node))
        assertNotNull(graph.getGroupNode("files", "root"))
        assertEquals("my-custom-id", graph.getGroupNode("files", "root")!!.id)
    }

    @Test
    fun `assignGroup unregistered group throws IllegalArgumentException`() {
        val node = graph.addNode("some-node")
        assertFailsWith<IllegalArgumentException> {
            graph.assignGroup(node, "unregistered")
        }
    }

    // endregion

    // region getGroupNode

    @Test
    fun `getGroupNode existing returns node`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "x")
        val retrieved = graph.getGroupNode("users", "x")
        assertNotNull(retrieved)
        assertEquals(node.id, retrieved.id)
    }

    @Test
    fun `getGroupNode nonexistent returns null`() {
        registerGroup("users")
        assertNull(graph.getGroupNode("users", "missing"))
    }

    @Test
    fun `getGroupNode empty group throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("", "suffix")
        }
    }

    @Test
    fun `getGroupNode empty suffix throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("users", "")
        }
    }

    // endregion

    // region getGroupName / getGroupSuffix / getGroupNodes

    @Test
    fun `getGroupName returns group from property`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "node1")
        assertEquals("users", graph.getGroupName(node))
    }

    @Test
    fun `getGroupName node without group returns null`() {
        val node = graph.addNode("plain")
        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `getGroupSuffix returns suffix from property`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "admin")
        assertEquals("admin", graph.getGroupSuffix(node))
    }

    @Test
    fun `getGroupNodes returns all nodes in group`() {
        registerGroup("users")
        registerGroup("products")
        graph.addGroupNode("users", "a")
        graph.addGroupNode("users", "b")
        graph.addGroupNode("products", "c")
        val userNodes = graph.getGroupNodes("users").toList()
        assertEquals(2, userNodes.size)
        assertTrue(userNodes.all { graph.getGroupName(it) == "users" })
    }

    @Test
    fun `getGroupNodes empty group returns empty`() {
        registerGroup("empty")
        assertEquals(0, graph.getGroupNodes("empty").count())
    }

    // endregion

    // region Counter and rebuild

    @Test
    fun `counter persisted to meta survives rebuild`() {
        registerGroup("users")
        graph.addGroupNode("users")
        graph.addGroupNode("users")
        graph.doRebuild()
        assertEquals(2, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `suffixIndex restored after rebuild`() {
        registerGroup("users")
        graph.addGroupNode("users", "admin")
        graph.doRebuild()
        val node = graph.getGroupNode("users", "admin")
        assertNotNull(node)
        assertEquals("users", graph.getGroupName(node))
    }

    @Test
    fun `addGroupNode after rebuild works`() {
        registerGroup("users")
        graph.addGroupNode("users")
        graph.doRebuild()
        val node = graph.addGroupNode("users")
        assertNotNull(node)
        assertEquals("users", graph.getGroupName(node))
    }

    @Test
    fun `counter never decreases after deletion`() {
        registerGroup("users")
        val n1 = graph.addGroupNode("users")
        graph.addGroupNode("users")
        graph.delNode(n1.id)
        val n3 = graph.addGroupNode("users")
        assertEquals("3", graph.getGroupSuffix(n3))
    }

    @Test
    fun `counter multiple groups are independent`() {
        registerGroup("users")
        registerGroup("products")
        graph.addGroupNode("users")
        graph.addGroupNode("users")
        graph.addGroupNode("products")
        assertEquals(2, graph.groupedNodesCounter["users"])
        assertEquals(1, graph.groupedNodesCounter["products"])
    }

    // endregion

    // region Rebuild edge cases

    @Test
    fun `rebuildGroupCaches on empty graph clears caches`() {
        registerGroup("users")
        graph.addGroupNode("users", "admin")
        graph.delNode(graph.getGroupNode("users", "admin")!!.id)
        graph.doRebuild()
        assertTrue(graph.groupedNodesCounter.isEmpty())
        assertTrue(graph.suffixIndex.isEmpty())
    }

    @Test
    fun `getGroupNode returns null for nonexistent suffix`() {
        registerGroup("users")
        graph.addGroupNode("users", "exists")
        assertNull(graph.getGroupNode("users", "ghost"))
    }

    // endregion

    // region ID format

    @Test
    fun `node id does not encode group information`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "admin")
        assertTrue(!node.id.contains("users"))
        assertTrue(!node.id.contains("admin"))
        assertTrue(node.id.startsWith("Test_"))
    }

    // endregion

    // region Branch coverage: assignGroup suffix paths

    @Test
    fun `assignGroup explicit suffix stores provided suffix`() {
        registerGroup("files")
        val node = graph.addNode("custom-node")

        graph.assignGroup(node, "files", "explicit")

        assertEquals("explicit", graph.getGroupSuffix(node))
    }

    @Test
    fun `assignGroup null suffix uses auto counter`() {
        registerGroup("files")
        val node = graph.addNode("auto-node")

        graph.assignGroup(node, "files")

        assertNotNull(graph.getGroupSuffix(node))
        assertEquals("1", graph.getGroupSuffix(node))
    }

    // endregion

    // region Branch coverage: addGroupNode global counter

    @Test
    fun `addGroupNode first call initializes global counter from zero`() {
        registerGroup("items")
        val meta = graph.storage.getMeta(TraitNodeGroup.META_GLOBAL_COUNTER)
        assertNull(meta)

        val node = graph.addGroupNode("items")

        val counter = graph.storage.getMeta(TraitNodeGroup.META_GLOBAL_COUNTER) as NumVal
        assertEquals(1, counter.toInt())
        assertTrue(node.id.endsWith("_1"))
    }

    @Test
    fun `addGroupNode second call reads existing global counter`() {
        registerGroup("items")
        graph.addGroupNode("items")

        val node2 = graph.addGroupNode("items")

        val counter = graph.storage.getMeta(TraitNodeGroup.META_GLOBAL_COUNTER) as NumVal
        assertEquals(2, counter.toInt())
        assertTrue(node2.id.endsWith("_2"))
    }

    // endregion

    // region Branch coverage: rebuildGroupCaches node property variants

    @Test
    fun `rebuildGroupCaches skips node with group but no suffix`() {
        registerGroup("data")
        val node = graph.addNode("partial-node")
        node[TraitNodeGroup.PROP_GROUP] = "data".strVal

        graph.doRebuild()

        assertTrue(graph.suffixIndex.isEmpty())
    }

    @Test
    fun `rebuildGroupCaches skips node without group or suffix`() {
        graph.addNode("plain-node")

        graph.doRebuild()

        assertTrue(graph.groupedNodesCounter.isEmpty())
        assertTrue(graph.suffixIndex.isEmpty())
    }

    @Test
    fun `rebuildGroupCaches persisted counter zero`() {
        registerGroup("grp")
        val node = graph.addNode("n1")
        node[TraitNodeGroup.PROP_GROUP] = "grp".strVal
        node[TraitNodeGroup.PROP_SUFFIX] = "s1".strVal

        graph.doRebuild()

        assertEquals(0, graph.groupedNodesCounter["grp"])
    }

    @Test
    fun `rebuildGroupCaches persisted counter positive`() {
        registerGroup("grp")
        graph.addGroupNode("grp", "a")
        graph.addGroupNode("grp", "b")
        val counterBefore = graph.groupedNodesCounter["grp"]
        assertEquals(2, counterBefore)

        graph.doRebuild()

        assertEquals(2, graph.groupedNodesCounter["grp"])
    }

    // endregion

    // region Branch coverage: getGroupNodes

    @Test
    fun `getGroupNodes returns nodes for populated group`() {
        registerGroup("team")
        graph.addGroupNode("team", "alice")
        graph.addGroupNode("team", "bob")

        val nodes = graph.getGroupNodes("team").toList()

        assertEquals(2, nodes.size)
    }

    @Test
    fun `getGroupNodes returns empty for group with no matching nodes`() {
        registerGroup("team")

        val nodes = graph.getGroupNodes("team").toList()

        assertTrue(nodes.isEmpty())
    }

    // endregion
}
