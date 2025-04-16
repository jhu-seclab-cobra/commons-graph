package cobra.common.graph.extension

import cobra.common.graph.extension.label.AbcBasicLabelLattice
import cobra.common.graph.extension.label.DefaultLatticeImpl
import cobra.common.graph.extension.label.JgraphtLatticeImpl
import cobra.common.graph.extension.label.Label
import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.toNid
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorage
import org.junit.jupiter.api.BeforeEach
import kotlin.collections.set
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test

abstract class AbcBasicLabelLatticeStressTest {

    class TestEdge(override val id: EdgeID, storage: IStorage) : AbcEdge(storage) {
        override val type get() = TODO("Not yet implemented")
    }

    private lateinit var lattice: AbcBasicLabelLattice
    private lateinit var storage: IStorage
    private val labels = mutableListOf<Label>()
    private val edges = mutableListOf<AbcEdge>()
    private val random = Random(42) // 用于生成一致的随机结构

    abstract fun newLattice(): AbcBasicLabelLattice

    @BeforeEach
    fun setUp() {
        lattice = newLattice() // 假设已有实现
        storage = NativeStorage() // 假设已有实现
        generateComplexLattice(10000) // 生成包含1万个标签的复杂结构
    }

    /**
     * Generates a complex lattice structure with multiple parents for each label and potential loops.
     * The generated lattice may include labels with multiple parents, forming a mesh and limited loops.
     */
    private fun generateComplexLattice(size: Int) = with(lattice) {
        for (i in 0 until size) {
            val label = Label("L$i")
            labels.add(label)

            if (i > 0) {
                // 随机为标签添加多个父标签，形成网状结构
                val parentCount = random.nextInt(1, 4) // 每个标签可能有1到3个父标签
                val parents = mutableMapOf<String, Label>()

                repeat(parentCount) {
                    val parentIndex = random.nextInt(i) // 选择之前生成的任意一个标签作为父标签
                    val parentLabel = labels[parentIndex]
                    parents["parent$it"] = parentLabel
                }
                label.parents = parents
            }
        }

        // 创建一些随机的边，并为边设置标签
        for (i in 0 until size / 10) {
            val srcID = edu.jhu.cobra.commons.graph.entity.toNid.also(storage::addNode)
            val dstID = edu.jhu.cobra.commons.graph.entity.toNid.also(storage::addNode)
            val edgeID = EdgeID(srcID, dstID, "edge$i").also(storage::addEdge)
            val edge = TestEdge(id = edgeID, storage = storage)
            edge.labels = setOf(labels[random.nextInt(size)], labels[random.nextInt(size)])
            edges.add(edge)
        }
    }

    @Test
    fun testCompareToPerformance() = with(lattice) {
        val time = measureTimeMillis {
            for (i in 0 until labels.size - 1) {
                labels[i].compareTo(labels[i + 1])
            }
        }
        println("Time taken for compareTo on a complex lattice: $time ms")
    }

    @Test
    fun testAncestorsPerformance() = with(lattice) {
        val time = measureTimeMillis {
            labels.forEach { label ->
                label.ancestors.toList()
            }
        }
        println("Time taken for ancestors property on a complex lattice: $time ms")
    }

    @Test
    fun testParentsPerformance() = with(lattice) {
        val time = measureTimeMillis {
            labels.forEach { label ->
                label.parents
            }
        }
        println("Time taken for parents property access on a complex lattice: $time ms")
    }

    @Test
    fun testStoreLatticePerformance() = with(lattice) {
        val time = measureTimeMillis {
            lattice.storeLattice(storage)
        }
        println("Time taken to store a complex lattice: $time ms")
    }

    @Test
    fun testLoadLatticePerformance() = with(lattice) {
        lattice.storeLattice(storage) // 预先存储数据以便加载测试
        val time = measureTimeMillis {
            lattice.loadLattice(storage)
        }
        println("Time taken to load a complex lattice: $time ms")
    }

    @Test
    fun testLabelsPropertyPerformance() = with(lattice) {
        val time = measureTimeMillis {
            edges.forEach { edge ->
                edge.labels = setOf(labels[random.nextInt(labels.size)])
                edge.labels
            }
        }
        println("Time taken for labels property on edges in a complex lattice: $time ms")
    }

    @Test
    fun testChangesPropertyPerformance() = with(lattice) {
        val time = measureTimeMillis {
            labels.forEach { label ->
                label.changes
            }
        }
        println("Time taken for changes property access on a complex lattice: $time ms")
    }
}

class JgraphtLatticeStressTest : AbcBasicLabelLatticeStressTest() {
    override fun newLattice(): AbcBasicLabelLattice = JgraphtLatticeImpl()
}

class DefaultLatticeStressTest : AbcBasicLabelLatticeStressTest() {
    override fun newLattice(): AbcBasicLabelLattice = DefaultLatticeImpl()
}
