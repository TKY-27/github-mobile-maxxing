package dev.maxxing.github.ui.components

import androidx.compose.ui.graphics.Color
import dev.maxxing.github.model.DiffHunk
import dev.maxxing.github.model.DiffLine
import dev.maxxing.github.model.DiffLineKind
import dev.maxxing.github.model.FileChangeStatus
import dev.maxxing.github.model.PullFileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeViewerUtilsTest {

    @Test
    fun `prepareCodeDisplayLines segments ultra long lines while preserving original line numbers`() {
        val longLine = "a".repeat(2500)
        val lines = prepareCodeDisplayLines("first\n$longLine\nthird", maxSegmentChars = 1000)

        assertEquals(5, lines.size)
        assertEquals(CodeDisplayLine(1, "first"), lines[0])
        assertEquals(2, lines[1].lineNumber)
        assertEquals(1000, lines[1].text.length)
        assertNull(lines[2].lineNumber)
        assertEquals(1000, lines[2].text.length)
        assertNull(lines[3].lineNumber)
        assertEquals(500, lines[3].text.length)
        assertEquals(CodeDisplayLine(3, "third"), lines[4])
    }

    @Test
    fun `highlightCodeLineFast highlights keywords strings numbers comments and skips huge lines safely`() {
        val highlighted = highlightCodeLineFast(
            line = """fun main() { val n = 42 // comment "str" }""",
            keywordColor = Color.Blue,
            stringColor = Color.Green,
            commentColor = Color.Gray,
            numberColor = Color.Magenta,
            defaultColor = Color.Black
        )
        assertEquals("""fun main() { val n = 42 // comment "str" }""", highlighted.text)
        assertTrue(highlighted.spanStyles.any { it.item.color == Color.Blue })
        assertTrue(highlighted.spanStyles.any { it.item.color == Color.Magenta })
        assertTrue(highlighted.spanStyles.any { it.item.color == Color.Gray })

        val strLine = highlightCodeLineFast(
            line = """val s = "hello \"world\"" """,
            keywordColor = Color.Blue,
            stringColor = Color.Green,
            commentColor = Color.Gray,
            numberColor = Color.Magenta,
            defaultColor = Color.Black
        )
        assertTrue(strLine.spanStyles.any { it.item.color == Color.Green })

        val minified = "const a=1;".repeat(200)
        val fastUniform = highlightCodeLineFast(
            line = minified,
            keywordColor = Color.Blue,
            stringColor = Color.Green,
            commentColor = Color.Gray,
            numberColor = Color.Magenta,
            defaultColor = Color.Black
        )
        assertEquals(1, fastUniform.spanStyles.size)
        assertEquals(minified, fastUniform.text)
    }

    @Test
    fun `computeDiffMaxLineDigits sizes gutter to at least 4 digits and expands for huge files`() {
        val emptyFile = PullFileChange(
            filename = "Empty.kt",
            status = FileChangeStatus.MODIFIED,
            additions = 0,
            deletions = 0
        )
        assertEquals(4, computeDiffMaxLineDigits(emptyFile))

        val hugeFile = PullFileChange(
            filename = "Huge.kt",
            status = FileChangeStatus.MODIFIED,
            additions = 1,
            deletions = 0,
            hunks = listOf(
                DiffHunk(
                    header = "@@ -12340,1 +12340,2 @@",
                    oldStart = 12340,
                    oldCount = 1,
                    newStart = 12340,
                    newCount = 2,
                    lines = listOf(
                        DiffLine(DiffLineKind.CONTEXT, "keep", oldLineNumber = 12340, newLineNumber = 12340),
                        DiffLine(DiffLineKind.ADDED, "added", oldLineNumber = null, newLineNumber = 100000)
                    )
                )
            )
        )
        assertEquals(6, computeDiffMaxLineDigits(hugeFile))
    }
}
