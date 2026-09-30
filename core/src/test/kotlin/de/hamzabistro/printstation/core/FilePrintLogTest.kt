package de.hamzabistro.printstation.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePrintLogTest {
    private val dir: File = Files.createTempDirectory("print-log").toFile()
    private val file = File(dir, "print-log.json")

    @Test
    fun `remembers across restarts`() {
        FilePrintLog(file).apply {
            markDone(listOf("a", "b"))
            setUnreported("b", true)
        }
        val again = FilePrintLog(file)
        assertTrue(again.isDone("a"))
        assertEquals(setOf("b"), again.unreported())
        again.setUnreported("b", false)
        assertTrue(FilePrintLog(file).unreported().isEmpty())
    }

    @Test
    fun `keeps only the most recent orders`() {
        val log = FilePrintLog(file, limit = 3)
        log.markDone(listOf("1", "2", "3", "4"))
        assertFalse(log.isDone("1"))
        assertTrue(log.isDone("4"))
    }

    @Test
    fun `a damaged file is an empty log`() {
        file.writeText("{not json")
        assertFalse(FilePrintLog(file).isDone("a"))
    }
}
