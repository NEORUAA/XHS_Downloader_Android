package com.neoruaa.xhsdn.data.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordExportTest {
    @Test fun csvEscapesQuotedMultilineValuesAndNeutralizesSpreadsheetFormulas() {
        assertEquals("\"a,\"\"b\"\"\nnext\"", csvCell("a,\"b\"\nnext"))
        assertEquals("\"'=1+1\"", csvCell("=1+1"))
        assertEquals("\"'  @SUM(A1)\"", csvCell("  @SUM(A1)"))
    }
}
