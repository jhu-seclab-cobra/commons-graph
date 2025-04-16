package cobra.common.graph.exchange

import edu.jhu.cobra.commons.graph.exchange.CsvExchangeImpl
import kotlin.io.path.exists
import kotlin.test.Test

class CsvFileExchangeTest : AbcGraphExchangeTest() {
    @Test
    fun exchangeCSV() = testExchange { (storageA, storageB) ->
        val graphDir = graphTestDir.resolve("graph.csvdir")
        if (graphDir.exists()) graphDir.toFile().deleteRecursively()
        println("exchangeGML based on the file: $graphDir")
        CsvExchangeImpl.export(dstFile = graphDir, from = storageA)
        CsvExchangeImpl.import(srcFile = graphDir, into = storageB)
    }
}