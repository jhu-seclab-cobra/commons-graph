package edu.jhu.cobra.commons.graph.exchange

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.*

class MapDbGraphExchangeImplTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var tempDir: Path

    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val edge = EdgeID(node1, node2, "testEdge")

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        tempDir = createTempDirectory("mapdb_test")
        prepareBasicData(storage)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // ----------- 文件有效性相关 -----------
    @Test
    fun `test isValidFile with valid file`() {
        val dbFile = tempDir.resolve("valid.db")
        MapDbGraphExchangeImpl.export(dbFile, storage) { true }
        assertTrue(MapDbGraphExchangeImpl.isValidFile(dbFile))
    }

    @Test
    fun `test isValidFile with invalid or missing file`() {
        val invalidFile = tempDir.resolve("invalid.db")
        invalidFile.writeText("")
        assertFalse(MapDbGraphExchangeImpl.isValidFile(invalidFile))
        val notExistFile = tempDir.resolve("notexist.db")
        assertFalse(MapDbGraphExchangeImpl.isValidFile(notExistFile))
    }

    // ----------- 基本导出导入功能 -----------
    @Test
    fun `test export and import basic functionality`() {
        val dbFile = tempDir.resolve("export_import.db")
        MapDbGraphExchangeImpl.export(dbFile, storage) { true }
        val newStorage = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newStorage) { true }
        assertStorageEquals(storage, newStorage)
    }

    @Test
    fun `test export and import with empty storage`() {
        val emptyStorage = NativeStorageImpl()
        val dbFile = tempDir.resolve("empty_storage.db")
        MapDbGraphExchangeImpl.export(dbFile, emptyStorage) { true }
        val newStorage = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newStorage) { true }
        assertEquals(0, newStorage.nodeIDsSequence.count())
        assertEquals(0, newStorage.edgeIDsSequence.count())
    }

    // ----------- 属性边界与特殊情况 -----------
    @Test
    fun `test export and import with null and empty properties`() {
        val s = NativeStorageImpl()
        val n = NodeID("n")
        s.addNode(n, "emptyProp" to "".strVal)
        val e = EdgeID(n, n, "e")
        s.addEdge(e, "emptyProp" to "".strVal)
        val dbFile = tempDir.resolve("null_empty_props.db")
        MapDbGraphExchangeImpl.export(dbFile, s) { true }
        val newS = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newS) { true }
        val props = newS.getNodeProperties(n)
        assertTrue("nullProp" !in props || props["nullProp"] == null)
        assertEquals("StrVal{}", props["emptyProp"]?.toString() ?: "")
        val edgeProps = newS.getEdgeProperties(e)
        assertTrue("nullProp" !in edgeProps || edgeProps["nullProp"] == null)
        assertEquals("StrVal{}", edgeProps["emptyProp"]?.toString() ?: "")
    }

    @Test
    fun `test export and import with special and complex properties`() {
        val s = NativeStorageImpl()
        val n = NodeID("specialNode")
        s.addNode(
            n,
            "special" to "a,b\nc\td\re\"f".strVal,
            "list" to ListVal(listOf("x".strVal, "y".strVal)),
            "map" to MapVal("k" to "v".strVal)
        )
        val dbFile = tempDir.resolve("special_complex.db")
        MapDbGraphExchangeImpl.export(dbFile, s) { true }
        val newS = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newS) { true }
        val props = newS.getNodeProperties(n)
        assertEquals("StrVal{a,b\nc\td\re\"f}", props["special"]?.toString()?.removePrefix("Str:"))
        assertEquals(ListVal(listOf("x".strVal, "y".strVal)), props["list"])
        assertTrue(props["map"].toString().contains("k"))
    }

    // ----------- 节点/边已存在、自动补全 -----------
    @Test
    fun `test import when node already exists`() {
        val dbFile = tempDir.resolve("exist_node.db")
        MapDbGraphExchangeImpl.export(dbFile, storage) { true }
        val newStorage = NativeStorageImpl()
        newStorage.addNode(node1, "strProp" to "old".strVal)
        MapDbGraphExchangeImpl.import(dbFile, newStorage) { true }
        assertEquals("StrVal{value1}", newStorage.getNodeProperty(node1, "strProp")?.toString()?.removePrefix("Str:"))
    }

    @Test
    fun `test import when edge already exists`() {
        val dbFile = tempDir.resolve("exist_edge.db")
        MapDbGraphExchangeImpl.export(dbFile, storage) { true }
        val newStorage = NativeStorageImpl()
        newStorage.addNode(node1)
        newStorage.addNode(node2)
        newStorage.addEdge(edge, "label" to "old".strVal)
        MapDbGraphExchangeImpl.import(dbFile, newStorage) { true }
        assertEquals("StrVal{test}", newStorage.getEdgeProperty(edge, "label")?.toString())
    }

    @Test
    fun `test import edge with missing node`() {
        val s = NativeStorageImpl()
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        s.addNode(n1, "p" to "v".strVal)
        s.addNode(n2, "p" to "v".strVal)
        val e = EdgeID(n1, n2, "e")
        s.addEdge(e, "p" to "v".strVal)
        val dbFile = tempDir.resolve("missing_node.db")
        MapDbGraphExchangeImpl.export(dbFile, s) { true }
        val newS = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newS) { true }
        assertTrue(newS.containsNode(n1))
        assertTrue(newS.containsNode(n2))
        assertTrue(newS.containsEdge(e))
    }

    // ----------- predicate 过滤 -----------
    @Test
    fun `test export and import with predicate (only node1)`() {
        val dbFile = tempDir.resolve("export_import_filtered.db")
        MapDbGraphExchangeImpl.export(dbFile, storage) { it is NodeID && it.name == "node1" }
        val newStorage = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newStorage) { true }
        assertEquals(1, newStorage.nodeIDsSequence.count())
        assertTrue(newStorage.containsNode(NodeID("node1")))
        assertFalse(newStorage.containsNode(NodeID("node2")))
        assertEquals(0, newStorage.edgeIDsSequence.count())
    }

    @Test
    fun `test import with predicate (only node2)`() {
        val dbFile = tempDir.resolve("export_import_predicate.db")
        MapDbGraphExchangeImpl.export(dbFile, storage) { true }
        val newStorage = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newStorage) { it is NodeID && it.name == "node2" }
        assertEquals(1, newStorage.nodeIDsSequence.count())
        assertTrue(newStorage.containsNode(NodeID("node2")))
        assertFalse(newStorage.containsNode(NodeID("node1")))
        assertEquals(0, newStorage.edgeIDsSequence.count())
    }

    @Test
    fun `test import with predicate only allows edges`() {
        val dbFile = tempDir.resolve("only_edges.db")
        MapDbGraphExchangeImpl.export(dbFile, storage) { true }
        val newStorage = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newStorage) { it is EdgeID }
        assertEquals(2, newStorage.nodeIDsSequence.count())
        assertEquals(1, newStorage.edgeIDsSequence.count())
    }

    // ----------- 大数据量 -----------
    @Test
    fun `test export and import large data`() {
        val s = NativeStorageImpl()
        val nodeCount = 200
        val edgeCount = 300
        val nodes = (1..nodeCount).map { NodeID("n$it") }
        nodes.forEach { s.addNode(it, "v" to it.name.strVal) }
        val edges = (1..edgeCount).map { EdgeID(nodes[it % nodeCount], nodes[(it * 2) % nodeCount], "e$it") }
        edges.forEach { s.addEdge(it, "w" to it.eType.strVal) }
        val dbFile = tempDir.resolve("large_data.db")
        MapDbGraphExchangeImpl.export(dbFile, s) { true }
        val newS = NativeStorageImpl()
        MapDbGraphExchangeImpl.import(dbFile, newS) { true }
        assertEquals(nodeCount, newS.nodeIDsSequence.count())
        assertEquals(edgeCount, newS.edgeIDsSequence.count())
    }

    // ----------- 异常处理 -----------
    @Test
    fun `test import with missing file or empty file`() {
        val notExistFile = tempDir.resolve("notexist2.db")
        assertFailsWith<IllegalArgumentException> {
            MapDbGraphExchangeImpl.import(notExistFile, NativeStorageImpl()) { true }
        }
        val emptyFile = tempDir.resolve("empty.db")
        emptyFile.writeText("")
        assertFailsWith<IllegalArgumentException> {
            MapDbGraphExchangeImpl.import(emptyFile, NativeStorageImpl()) { true }
        }
    }

    // ----------- 工具方法 -----------
    private fun prepareBasicData(s: NativeStorageImpl) {
        s.addNode(
            node1,
            "strProp" to "value1".strVal,
            "intProp" to 42.numVal,
            "boolProp" to true.boolVal
        )
        s.addNode(
            node2,
            "strProp" to "value2".strVal,
            "listProp" to ListVal(listOf(1.numVal, 2.numVal))
        )
        s.addEdge(
            edge,
            "weight" to 1.5.numVal,
            "label" to "test".strVal
        )
    }

    private fun assertStorageEquals(expected: NativeStorageImpl, actual: NativeStorageImpl) {
        assertEquals(expected.nodeIDsSequence.count(), actual.nodeIDsSequence.count())
        assertEquals(expected.edgeIDsSequence.count(), actual.edgeIDsSequence.count())
        expected.nodeIDsSequence.forEach { nodeId ->
            val originalProps = expected.getNodeProperties(nodeId)
            val importedProps = actual.getNodeProperties(nodeId)
            assertEquals(originalProps, importedProps)
        }
        expected.edgeIDsSequence.forEach { edgeId ->
            val originalProps = expected.getEdgeProperties(edgeId)
            val importedProps = actual.getEdgeProperties(edgeId)
            assertEquals(originalProps, importedProps)
        }
    }
}