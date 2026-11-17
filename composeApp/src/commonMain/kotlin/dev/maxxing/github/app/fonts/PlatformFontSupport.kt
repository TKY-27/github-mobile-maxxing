package dev.maxxing.github.app.fonts

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import dev.maxxing.github.model.CustomFontFile
import dev.maxxing.github.model.FontSelection

expect object PlatformFontManager {
    fun listSystemFontFamilies(): List<String>
    fun listImportedFonts(): List<CustomFontFile>
    fun deleteImportedFont(filePath: String): Boolean

    /**
     * Whether the fallback font is used glyph by glyph after the primary font. When false, the
     * fallback setting has no effect on this platform version.
     */
    val supportsLayeredFallback: Boolean
}

@Composable
expect fun rememberFontFileImporter(onImported: (CustomFontFile?) -> Unit): () -> Unit

@Composable
expect fun ProvideAppTypographyFont(
    primary: FontSelection,
    fallback: FontSelection,
    content: @Composable (FontFamily?) -> Unit
)
