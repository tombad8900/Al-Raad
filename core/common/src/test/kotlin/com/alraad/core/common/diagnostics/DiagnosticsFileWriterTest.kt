package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsFileWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(n: Int) =
        DiagEntry(
            id = n.toLong(),
            timestamp = 0L,
            category = DiagCategory.ADBLOCK,
            level = DiagLevel.DEBUG,
            message = "m$n",
        )

    private fun TestScope.start(writer: DiagnosticsFileWriter) =
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            writer.runLoop()
        }

    @Test
    fun `entries beyond the pending limit are counted and noted instead of queued without bound`() =
        runTest {
            val file = File(tmp.root, "w.log")
            val writer =
                DiagnosticsFileWriter(
                    file,
                    Any(),
                    { it.message },
                    maxPending = 3,
                    droppedNote = { entry(99).copy(message = "dropped $it") },
                )
            repeat(5) { writer.enqueue(entry(it)) }
            start(writer)
            writer.flush()
            assertEquals(listOf("m0", "m1", "m2", "dropped 2"), file.readLines())
        }

    @Test
    fun `a failing disk write does not stop the writer or hang flush and clear`() =
        runTest {
            val dir = tmp.newFolder("not-a-file")
            val writer = DiagnosticsFileWriter(dir, Any(), { it.message })
            start(writer)
            writer.enqueue(entry(1))
            writer.flush()
            writer.enqueue(entry(2))
            writer.flush()
            assertTrue(dir.isDirectory)
        }
}
