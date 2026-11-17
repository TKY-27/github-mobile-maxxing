package dev.maxxing.github.auth

import dev.maxxing.github.model.FontSelection
import dev.maxxing.github.model.FontSourceKind
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePreferencesTest {
    // The settings the Android and Desktop token stores use.
    private val json = TokenStoreJson

    @Test
    fun legacyJsonWithoutFontFallsBackToDefaults() {
        val legacyJson = """{"seedColorHex":16734003,"useDynamicColor":false,"darkModeOverride":true}"""
        val decoded = json.decodeFromString<ThemePreferences>(legacyJson)

        assertEquals(16734003L, decoded.seedColorHex)
        assertEquals(false, decoded.useDynamicColor)
        assertEquals(true, decoded.darkModeOverride)
        assertEquals(FontSelection.SystemDefault, decoded.primaryFont)
        assertEquals(FontSelection.SystemDefault, decoded.fallbackFont)
    }

    @Test
    fun layeredFontsRoundTripThroughJson() {
        val systemPrimary = FontSelection(
            kind = FontSourceKind.SYSTEM_FAMILY,
            value = "Noto Sans",
            displayName = "Noto Sans"
        )
        val customFallback = FontSelection(
            kind = FontSourceKind.CUSTOM_FILE,
            value = "/tmp/custom.ttf",
            displayName = "CustomJP"
        )
        val prefs = ThemePreferences(
            primaryFont = systemPrimary,
            fallbackFont = customFallback
        )
        val encoded = json.encodeToString(prefs)
        val decoded = json.decodeFromString<ThemePreferences>(encoded)

        assertEquals(systemPrimary, decoded.primaryFont)
        assertEquals(customFallback, decoded.fallbackFont)
    }

    @Test
    fun unknownFontKindFallsBackToDefaultWithoutLosingOtherPreferences() {
        val newerJson = """{"seedColorHex":16734003,"useDynamicColor":false,""" +
            """"primaryFont":{"kind":"SOME_FUTURE_KIND","value":"x","displayName":"X"},""" +
            """"fallbackFont":{"kind":"SERIF_CJK_MAGIC"}}"""
        val decoded = json.decodeFromString<ThemePreferences>(newerJson)

        assertEquals(16734003L, decoded.seedColorHex)
        assertEquals(false, decoded.useDynamicColor)
        assertEquals(FontSourceKind.SYSTEM_DEFAULT, decoded.primaryFont.kind)
        assertEquals(FontSelection.SystemDefault, decoded.fallbackFont)
    }

    @Test
    fun customFileLabelUsesTheFileNameOnEveryPlatform() {
        assertEquals("Foo.ttf", FontSelection(FontSourceKind.CUSTOM_FILE, "/home/u/fonts/Foo.ttf").displayLabel())
        assertEquals("Foo.ttf", FontSelection(FontSourceKind.CUSTOM_FILE, "C:\\Users\\u\\fonts\\Foo.ttf").displayLabel())
        assertEquals("Shown", FontSelection(FontSourceKind.CUSTOM_FILE, "/x/Foo.ttf", "Shown").displayLabel())
    }
}
