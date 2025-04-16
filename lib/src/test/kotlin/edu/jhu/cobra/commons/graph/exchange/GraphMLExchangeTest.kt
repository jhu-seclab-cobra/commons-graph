package edu.jhu.cobra.commons.graph.exchange

import kotlin.io.path.deleteIfExists
import kotlin.test.Test

class GraphMLExchangeTest : AbcGraphExchangeTest() {
    @Test
    fun exchangeGML() = testExchange { (storageA, storageB) ->
        val file = graphTestDir.resolve("graph.gml").also { it.deleteIfExists() }
        println("exchangeGML based on the file: $file")
        GmlExchangeImpl.export(dstFile = file, from = storageA)
        GmlExchangeImpl.import(srcFile = file, into = storageB)
    }
}