package dev.maxxing.github.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorPickerUtilsTest {
    @Test
    fun `hsv and hex conversions round-trip accurately`() {
        val samples = listOf(
            0xFF6750A4L,
            0xFF3B6E8CL,
            0xFF4A7C59L,
            0xFFB85D43L,
            0xFFFFFFFFL,
            0xFF000000L
        )
        samples.forEach { expected ->
            val (h, s, v) = colorHexToHsv(expected)
            val roundTripped = hsvToColorHex(h, s, v)
            assertEquals(expected, roundTripped, "Round-trip failed for ${formatSeedColorHex(expected)}")
        }
    }

    @Test
    fun `parseHexColorInput and formatSeedColorHex handle valid and invalid inputs`() {
        assertEquals(0xFF3B6E8CL, parseHexColorInput("#3b6e8c"))
        assertEquals(0xFF4A7C59L, parseHexColorInput(" 4A7C59 "))
        assertEquals("#3B6E8C", formatSeedColorHex(0xFF3B6E8CL))
        assertNull(parseHexColorInput("#12345"))
        assertNull(parseHexColorInput("#GGGGGG"))
        assertTrue(hsvToColorHex(0f, 1f, 1f) == 0xFFFF0000L)
    }
}
