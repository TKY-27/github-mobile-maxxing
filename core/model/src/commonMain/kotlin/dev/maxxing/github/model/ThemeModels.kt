package dev.maxxing.github.model

import kotlinx.serialization.Serializable

@Serializable
enum class FontSourceKind {
    SYSTEM_DEFAULT,
    GENERIC_SANS,
    GENERIC_SERIF,
    GENERIC_MONOSPACE,
    SYSTEM_FAMILY,
    CUSTOM_FILE
}

@Serializable
data class FontSelection(
    val kind: FontSourceKind = FontSourceKind.SYSTEM_DEFAULT,
    val value: String = "",
    val displayName: String = ""
) {
    fun displayLabel(): String = when (kind) {
        FontSourceKind.SYSTEM_DEFAULT -> "System Default"
        FontSourceKind.GENERIC_SANS -> "Sans-serif"
        FontSourceKind.GENERIC_SERIF -> "Serif"
        FontSourceKind.GENERIC_MONOSPACE -> "Monospace"
        FontSourceKind.SYSTEM_FAMILY -> displayName.ifBlank { value }.ifBlank { "System Default" }
        FontSourceKind.CUSTOM_FILE -> displayName.ifBlank { value.substringAfterLast('/').substringAfterLast('\\') }
    }

    companion object {
        val SystemDefault = FontSelection(FontSourceKind.SYSTEM_DEFAULT)
        val SansSerif = FontSelection(FontSourceKind.GENERIC_SANS, displayName = "Sans-serif")
        val Serif = FontSelection(FontSourceKind.GENERIC_SERIF, displayName = "Serif")
        val Monospace = FontSelection(FontSourceKind.GENERIC_MONOSPACE, displayName = "Monospace")
    }
}

data class CustomFontFile(
    val id: String,
    val displayName: String,
    val filePath: String
)
