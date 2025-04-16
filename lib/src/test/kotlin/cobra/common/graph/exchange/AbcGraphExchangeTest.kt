package cobra.common.graph.exchange

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.toNid
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.JgphtStorage
import edu.jhu.cobra.commons.value.BoolVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class AbcGraphExchangeTest {

    private val tmpDirectory = Path.of(System.getProperty("java.io.tmpdir"), "cobra")
    protected val graphTestDir: Path = tmpDirectory.resolve("graph").also { it.createDirectories() }

    protected fun testExchange(exchange: (Pair<IStorage, IStorage>) -> Unit) {
        val (storageA, storageB) = JgphtStorage() to JgphtStorage()
        val (firstNode, secondNode) = edu.jhu.cobra.commons.graph.entity.toNid to edu.jhu.cobra.commons.graph.entity.toNid
        val firstEdge = EdgeID(firstNode, secondNode, "edge1")
        val secondEdge = EdgeID(secondNode, firstNode, "edge2")
        storageA.addNode(firstNode, "prop1" to "value1".strVal)
        storageA.addNode(secondNode, "prop2" to 2.numVal)
        storageA.addEdge(firstEdge, "prop3" to "value3".strVal)
        storageA.addEdge(secondEdge, "prop4" to true.boolVal)
        exchange(storageA to storageB)
        assertTrue(storageB.containsNode(firstNode))
        assertTrue(storageB.containsNode(secondNode))
        assertTrue(storageB.containsEdge(firstEdge))
        assertTrue(storageB.containsEdge(secondEdge))
        assertEquals("value1".strVal, storageB.getNodeProperty(firstNode, "prop1"))
        assertEquals(2.numVal, storageB.getNodeProperty(secondNode, "prop2"))
        assertEquals("value3".strVal, storageB.getEdgeProperty(firstEdge, "prop3"))
        assertTrue(storageB.getEdgeProperty(secondEdge, "prop4") is BoolVal)
    }
}