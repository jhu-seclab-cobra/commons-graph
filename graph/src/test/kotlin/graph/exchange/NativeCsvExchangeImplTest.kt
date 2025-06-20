package graph.exchange

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvExchangeImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class NativeCsvExchangeImplTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        tempDir = createTempDirectory("csv_test")

        // 创建测试数据
        val node1 = NodeID("node1")
        val node2 = NodeID("node2")
        storage.addNode(
            node1,
            "strProp" to "value1".strVal,
            "intProp" to 42.numVal,
            "boolProp" to true.boolVal
        )
        storage.addNode(
            node2,
            "strProp" to "value2".strVal,
            "listProp" to ListVal(listOf(1.numVal, 2.numVal))
        )

        val edge = EdgeID(node1, node2, "testEdge")
        storage.addEdge(
            edge,
            "weight" to 1.5.numVal,
            "label" to "test".strVal
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test isValidFile with valid directory`() {
        // 准备有效的CSV文件
        val csvDir = tempDir.resolve("valid")
        csvDir.createDirectories()

        val nodesFile = csvDir.resolve("nodes.csv")
        nodesFile.writeText("ID\tprop1\nnode1\tvalue1\n")

        val edgesFile = csvDir.resolve("edges.csv")
        edgesFile.writeText("ID\tprop1\nedge1\tvalue1\n")

        assertTrue(NativeCsvExchangeImpl.isValidFile(csvDir))
    }

    @Test
    fun `test isValidFile with invalid cases`() {
        // 测试不存在的目录
        val nonExistentDir = tempDir.resolve("nonexistent")
        assertFalse(NativeCsvExchangeImpl.isValidFile(nonExistentDir))

        // 测试空目录
        val emptyDir = tempDir.resolve("empty")
        emptyDir.createDirectories()
        assertFalse(NativeCsvExchangeImpl.isValidFile(emptyDir))

        // 测试缺少文件的目录
        val incompleteDir = tempDir.resolve("incomplete")
        incompleteDir.createDirectories()
        incompleteDir.resolve("nodes.csv").writeText("")
        assertFalse(NativeCsvExchangeImpl.isValidFile(incompleteDir))
    }

    @Test
    fun `test export basic functionality`() {
        val exportDir = tempDir.resolve("export")
        NativeCsvExchangeImpl.export(exportDir, storage) { true }

        // 验证文件创建
        assertTrue(exportDir.exists())
        assertTrue(exportDir.resolve("nodes.csv").exists())
        assertTrue(exportDir.resolve("edges.csv").exists())

        // 验证文件内容
        val nodesContent = exportDir.resolve("nodes.csv").readLines()
        val edgesContent = exportDir.resolve("edges.csv").readLines()

        // 验证头部
        assertTrue(nodesContent[0].startsWith("ID"))
        assertTrue(edgesContent[0].startsWith("ID"))

        // 验证数据行数
        assertEquals(3, nodesContent.size) // 头部 + 2个节点
        assertEquals(2, edgesContent.size) // 头部 + 1个边
    }

    @Test
    fun `test export with predicate`() {
        val exportDir = tempDir.resolve("export_filtered")

        // 只导出带有 strProp 的实体
        NativeCsvExchangeImpl.export(exportDir, storage) { id ->
            when (id) {
                is NodeID -> storage.getNodeProperty(id, "strProp") != null
                is EdgeID -> false
            }
        }

        val nodesContent = exportDir.resolve("nodes.csv").readLines()
        assertEquals(3, nodesContent.size) // 头部 + 2个节点（都有strProp）

        val edgesContent = exportDir.resolve("edges.csv").readLines()
        assertEquals(1, edgesContent.size) // 只有头部，没有边数据
    }

    @Test
    fun `test export with special characters`() {
        // 添加包含特殊字符的数据
        val specialNode = NodeID("special")
        storage.addNode(
            specialNode,
            "with\ttab" to "tab\tvalue".strVal,
            "with\nnewline" to "newline\nvalue".strVal,
            "with\rreturn" to "return\rvalue".strVal
        )

        val exportDir = tempDir.resolve("export_special")
        NativeCsvExchangeImpl.export(exportDir, storage) { true }

        // 验证导出的文件可以被正确读取
        val nodesContent = exportDir.resolve("nodes.csv").readLines()
        assertTrue(nodesContent.size > 1)

        // 验证特殊字符被正确转义
        val specialLine = nodesContent.find { it.startsWith("Str:7:special") }
        assertNotNull(specialLine)
        assertTrue(specialLine.contains("\\t"))
        assertTrue(specialLine.contains("\\n"))
        assertTrue(specialLine.contains("\\r"))
    }

    @Test
    fun `test import basic functionality`() {
        // 先导出数据
        val exportDir = tempDir.resolve("export_for_import")
        NativeCsvExchangeImpl.export(exportDir, storage) { true }

        // 创建新的存储并导入
        val newStorage = NativeStorageImpl()
        NativeCsvExchangeImpl.import(exportDir, newStorage) { true }

        // 验证节点数量
        assertEquals(
            storage.nodeIDsSequence.count(),
            newStorage.nodeIDsSequence.count()
        )

        // 验证边数量
        assertEquals(
            storage.edgeIDsSequence.count(),
            newStorage.edgeIDsSequence.count()
        )

        // 验证节点属性
        storage.nodeIDsSequence.forEach { nodeId ->
            val originalProps = storage.getNodeProperties(nodeId)
            val importedProps = newStorage.getNodeProperties(nodeId)
            assertEquals(originalProps, importedProps)
        }

        // 验证边属性
        storage.edgeIDsSequence.forEach { edgeId ->
            val originalProps = storage.getEdgeProperties(edgeId)
            val importedProps = newStorage.getEdgeProperties(edgeId)
            assertEquals(originalProps, importedProps)
        }
    }

    @Test
    fun `test import with predicate`() {
        // 先导出所有数据
        val exportDir = tempDir.resolve("export_for_filtered_import")
        NativeCsvExchangeImpl.export(exportDir, storage) { true }

        // 创建新的存储并只导入特定节点
        val newStorage = NativeStorageImpl()
        NativeCsvExchangeImpl.import(exportDir, newStorage) { id ->
            id is NodeID && id.name == "node1"
        }

        // 验证只导入了一个节点
        assertEquals(1, newStorage.nodeIDsSequence.count())
        assertTrue(newStorage.containsNode(NodeID("node1")))
        assertFalse(newStorage.containsNode(NodeID("node2")))
    }

    @Test
    fun `test import with invalid files`() {
        val invalidDir = tempDir.resolve("invalid_import")
        invalidDir.createDirectories()

        // 测试缺少文件的情况
        assertFailsWith<IllegalArgumentException> {
            NativeCsvExchangeImpl.import(invalidDir, NativeStorageImpl()) { true }
        }

        // 测试空文件的情况
        invalidDir.resolve("nodes.csv").writeText("")
        invalidDir.resolve("edges.csv").writeText("")
        assertFailsWith<IllegalArgumentException> {
            NativeCsvExchangeImpl.import(invalidDir, NativeStorageImpl()) { true }
        }
    }

    @Test
    fun `test import with malformed data`() {
        val malformedDir = tempDir.resolve("malformed_import")
        malformedDir.createDirectories()

        // 创建格式错误的文件
        malformedDir.resolve("nodes.csv").writeText(
            """
            ID    prop1
            invalidNode    not_a_value
            node1    {type:STR,value:"valid"}
        """.trimIndent().replace("    ", "\t")
        )

        malformedDir.resolve("edges.csv").writeText(
            """
            ID    prop1
            invalidEdge    not_a_value
            node1-type-node2    {type:STR,value:"valid"}
        """.trimIndent().replace("    ", "\t")
        )

        // 导入应该跳过错误的行
        val newStorage = NativeStorageImpl()

        NativeCsvExchangeImpl.import(malformedDir, newStorage) { true }

        // 验证只导入了有效的数据
        assertEquals(0, newStorage.nodeIDsSequence.count())
        assertEquals(0, newStorage.edgeIDsSequence.count())
    }
}
