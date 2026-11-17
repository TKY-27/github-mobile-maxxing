package dev.maxxing.github.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import dev.maxxing.github.model.FontSelection
import dev.maxxing.github.model.FontSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class MaxxingThemeCustomizationTest {
    @Test
    fun buildMaxxingTypographyWithoutOverrideReturnsOriginalInstance() {
        assertSame(MaxxingTypography, buildMaxxingTypography(null))
        assertSame(MaxxingTypography, buildMaxxingTypography())
    }

    @Test
    fun buildMaxxingTypographyAppliesOverrideExceptMonospaceLabelSmall() {
        val base = MaxxingTypography
        val customFamily = FontFamily.Serif
        val updated = buildMaxxingTypography(customFamily)

        val overridden: List<Pair<TextStyle, TextStyle>> = listOf(
            base.displayLarge to updated.displayLarge,
            base.displayMedium to updated.displayMedium,
            base.displaySmall to updated.displaySmall,
            base.headlineLarge to updated.headlineLarge,
            base.headlineMedium to updated.headlineMedium,
            base.headlineSmall to updated.headlineSmall,
            base.titleLarge to updated.titleLarge,
            base.titleMedium to updated.titleMedium,
            base.titleSmall to updated.titleSmall,
            base.bodyLarge to updated.bodyLarge,
            base.bodyMedium to updated.bodyMedium,
            base.bodySmall to updated.bodySmall,
            base.labelLarge to updated.labelLarge,
            base.labelMedium to updated.labelMedium,
            base.displayLargeEmphasized to updated.displayLargeEmphasized,
            base.displayMediumEmphasized to updated.displayMediumEmphasized,
            base.displaySmallEmphasized to updated.displaySmallEmphasized,
            base.headlineLargeEmphasized to updated.headlineLargeEmphasized,
            base.headlineMediumEmphasized to updated.headlineMediumEmphasized,
            base.headlineSmallEmphasized to updated.headlineSmallEmphasized,
            base.titleLargeEmphasized to updated.titleLargeEmphasized,
            base.titleMediumEmphasized to updated.titleMediumEmphasized,
            base.titleSmallEmphasized to updated.titleSmallEmphasized,
            base.bodyLargeEmphasized to updated.bodyLargeEmphasized,
            base.bodyMediumEmphasized to updated.bodyMediumEmphasized,
            base.bodySmallEmphasized to updated.bodySmallEmphasized,
            base.labelLargeEmphasized to updated.labelLargeEmphasized,
            base.labelMediumEmphasized to updated.labelMediumEmphasized
        )
        overridden.forEachIndexed { index, (before, after) ->
            assertEquals(customFamily, after.fontFamily, "style #$index should use the override family")
            // Everything except the family (weight, size, line height, spacing) is preserved.
            assertEquals(before.copy(fontFamily = customFamily), after, "style #$index changed more than its family")
        }

        assertEquals(base.labelSmall, updated.labelSmall)
        assertEquals(FontFamily.Monospace, updated.labelSmall.fontFamily)
        assertEquals(base.labelSmallEmphasized, updated.labelSmallEmphasized)
        assertNotEquals(customFamily, updated.labelSmallEmphasized.fontFamily)
    }

    @Test
    fun fontSelectionDisplayLabelsFormatAllSourceKinds() {
        assertEquals("System Default", FontSelection.SystemDefault.displayLabel())
        assertEquals("Sans-serif", FontSelection.SansSerif.displayLabel())
        assertEquals("Serif", FontSelection.Serif.displayLabel())
        assertEquals("Monospace", FontSelection.Monospace.displayLabel())

        val systemFont = FontSelection(
            kind = FontSourceKind.SYSTEM_FAMILY,
            value = "Roboto",
            displayName = ""
        )
        assertEquals("Roboto", systemFont.displayLabel())

        val customPathOnly = FontSelection(
            kind = FontSourceKind.CUSTOM_FILE,
            value = "/tmp/fonts/MyCustomFont.otf",
            displayName = ""
        )
        assertEquals("MyCustomFont.otf", customPathOnly.displayLabel())

        val customWithName = FontSelection(
            kind = FontSourceKind.CUSTOM_FILE,
            value = "/tmp/fonts/MyCustomFont.otf",
            displayName = "MyCustomFont"
        )
        assertEquals("MyCustomFont", customWithName.displayLabel())
    }
}
