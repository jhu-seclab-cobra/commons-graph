package edu.jhu.cobra.commons.graph.exchange

import kotlin.io.path.deleteIfExists
import kotlin.test.Test

class FilesDBExchangeTest : AbcGraphExchangeTest() {
    @Test
    fun exchangeFileDB() = testExchange { (storageA, storageB) ->
        val file = graphTestDir.resolve("graph.mapdb").also { it.deleteIfExists() }
        println("exchangeGML based on the file: $file")
        FdbExchangeImpl.export(dstFile = file, from = storageA)
        FdbExchangeImpl.import(srcFile = file, into = storageB)
    }
}