package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-box tests for TraitNodeGroup: groupPrefix, addGroupNode, getGroupNode, getGroupName,
 * node ID format, auto-increment counter.
 *
 * - `addGroupNode auto suffix generates prefix at group hash counter id` — verifies ID format
 * - `addGroupNode increments counter` — verifies monotonic counter
 * - `addGroupNode custom suffix uses provided suffix` — verifies explicit suffix
 * - `addGroupNode custom suffix still increments counter` — verifies counter always advances
 * - `addGroupNode null suffix uses auto suffix` — verifies null falls back to counter
 * - `addGroupNode unregistered group throws IllegalArgumentException` — verifies registration guard
 * - `addGroupNode empty group throws IllegalArgumentException` — verifies empty group guard
 * - `addGroupNode group containing at throws IllegalArgumentException` — verifies at-char guard
 * - `addGroupNode group containing hash throws IllegalArgumentException` — verifies hash-char guard
 * - `addGroupNode empty suffix throws IllegalArgumentException` — verifies empty suffix guard
 * - `addGroupNode suffix containing hash throws IllegalArgumentException` — verifies hash-in-suffix guard
 * - `addGroupNode duplicate node id throws EntityAlreadyExistException` — verifies uniqueness
 * - `addGroupNode same suffix different groups creates different nodes` — verifies group isolation
 * - `addGroupNode from sameGroupNode extracts group` — verifies group extraction
 * - `addGroupNode from sameGroupNode custom suffix` — verifies suffix override
 * - `addGroupNode from invalid node format throws IllegalArgumentException` — verifies parse guard
 * - `addGroupNode from unregistered group node throws IllegalArgumentException` — verifies registration
 * - `getGroupNode existing returns node` — verifies retrieval
 * - `getGroupNode nonexistent returns null` — verifies absent case
 * - `getGroupNode empty group throws IllegalArgumentException` — verifies group guard
 * - `getGroupNode empty suffix throws IllegalArgumentException` — verifies suffix guard
 * - `getGroupNode group containing at throws IllegalArgumentException` — verifies at-char guard
 * - `getGroupNode suffix containing hash throws IllegalArgumentException` — verifies hash guard
 * - `getGroupName valid format returns group name` — verifies parsing
 * - `getGroupName missing at returns null` — verifies invalid format
 * - `getGroupName missing hash returns null` — verifies invalid format
 * - `getGroupName at at start returns null` — verifies prefix required
 * - `getGroupName empty group segment returns null` — verifies non-empty group
 * - `counter starts at zero after registration` — verifies initial value
 * - `counter never decreases after deletion` — verifies monotonicity
 * - `counter multiple groups are independent` — verifies group isolation
 * - `node id format three parts prefix at group hash suffix` — verifies structure
 * - `groupPrefix at characters stripped in node id` — verifies sanitization
 */
internal class TraitNodeGroupTest : AbcTraitNodeGroupTest() {

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
    }

    // region addGroupNode(group, suffix)

    @Test
    fun `addGroupNode auto suffix generates prefix at group hash counter id`() {
        registerGroup("users")

        val node = graph.addGroupNode("users")

        assertEquals("Test@users#1", node.id)
        assertTrue(graph.containNode(node.id))
    }

    @Test
    fun `addGroupNode increments counter`() {
        registerGroup("users")
        graph.addGroupNode("users")
        graph.addGroupNode("users")

        assertEquals(2, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `addGroupNode custom suffix uses provided suffix`() {
        registerGroup("users")

        val node = graph.addGroupNode("users", "custom")

        assertEquals("Test@users#custom", node.id)
    }

    @Test
    fun `addGroupNode custom suffix still increments counter`() {
        registerGroup("users")
        graph.addGroupNode("users", "custom")

        val auto = graph.addGroupNode("users")

        assertEquals("Test@users#2", auto.id)
    }

    @Test
    fun `addGroupNode null suffix uses auto suffix`() {
        registerGroup("users")

        val node = graph.addGroupNode("users", null)

        assertEquals("Test@users#1", node.id)
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
    fun `addGroupNode group containing at throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("user@group")
        }
    }

    @Test
    fun `addGroupNode group containing hash throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("user#group")
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
    fun `addGroupNode suffix containing hash throws IllegalArgumentException`() {
        registerGroup("users")

        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode("users", "suf#fix")
        }
    }

    @Test
    fun `addGroupNode duplicate node id throws EntityAlreadyExistException`() {
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

        assertEquals("Test@users#item1", user.id)
        assertEquals("Test@products#item1", product.id)
    }

    // endregion

    // region addGroupNode(sameGroupNode, suffix)

    @Test
    fun `addGroupNode from sameGroupNode extracts group`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")

        val node2 = graph.addGroupNode(node1)

        assertEquals("Test@users#2", node2.id)
    }

    @Test
    fun `addGroupNode from sameGroupNode custom suffix`() {
        registerGroup("users")
        val node1 = graph.addGroupNode("users")

        val node2 = graph.addGroupNode(node1, "custom")

        assertEquals("Test@users#custom", node2.id)
    }

    @Test
    fun `addGroupNode from invalid node format throws IllegalArgumentException`() {
        val node = graph.addNode("no-at-or-hash")

        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
        }
    }

    @Test
    fun `addGroupNode from unregistered group node throws IllegalArgumentException`() {
        val node = graph.addNode("Test@unregistered#1")

        assertFailsWith<IllegalArgumentException> {
            graph.addGroupNode(node)
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

    @Test
    fun `getGroupNode group containing at throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("user@group", "suffix")
        }
    }

    @Test
    fun `getGroupNode suffix containing hash throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            graph.getGroupNode("users", "suf#fix")
        }
    }

    // endregion

    // region getGroupName

    @Test
    fun `getGroupName valid format returns group name`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "node1")

        assertEquals("users", graph.getGroupName(node))
    }

    @Test
    fun `getGroupName missing at returns null`() {
        val node = graph.addNode("no-at-symbol")

        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `getGroupName missing hash returns null`() {
        val node = graph.addNode("Test@group-only")

        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `getGroupName at at start returns null`() {
        val node = graph.addNode("@group#suffix")

        assertNull(graph.getGroupName(node))
    }

    @Test
    fun `getGroupName empty group segment returns null`() {
        val node = graph.addNode("Test@#suffix")

        assertNull(graph.getGroupName(node))
    }

    // endregion

    // region Counter behavior

    @Test
    fun `counter starts at zero after registration`() {
        registerGroup("users")

        assertEquals(0, graph.groupedNodesCounter["users"])
    }

    @Test
    fun `counter never decreases after deletion`() {
        registerGroup("users")
        val n1 = graph.addGroupNode("users")
        graph.addGroupNode("users")
        graph.delNode(n1.id)

        val n3 = graph.addGroupNode("users")

        assertEquals("Test@users#3", n3.id)
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

    // region Node ID format

    @Test
    fun `node id format three parts prefix at group hash suffix`() {
        registerGroup("users")
        val node = graph.addGroupNode("users", "custom")
        val parts = node.id.split("@", "#")

        assertEquals(3, parts.size)
        assertEquals("Test", parts[0])
        assertEquals("users", parts[1])
        assertEquals("custom", parts[2])
    }

    @Test
    fun `groupPrefix at characters stripped in node id`() {
        val atGraph = object :
            edu.jhu.cobra.commons.graph.AbcSimpleGraph<TestNode, TestEdge>(),
            TraitNodeGroup<TestNode, TestEdge> {
            override val storage = edu.jhu.cobra.commons.graph.storage.NativeStorageImpl()
            override val posetStorage = edu.jhu.cobra.commons.graph.storage.NativeStorageImpl()
            override val groupPrefix: String = "Te@st"
            override val groupedNodesCounter: MutableMap<String, Int> = mutableMapOf()

            override fun newNodeObj() = TestNode()
            override fun newEdgeObj() = TestEdge()
        }
        atGraph.groupedNodesCounter["g"] = 0

        val node = atGraph.addGroupNode("g", "s")

        assertEquals("Test@g#s", node.id)
    }

    // endregion
}
