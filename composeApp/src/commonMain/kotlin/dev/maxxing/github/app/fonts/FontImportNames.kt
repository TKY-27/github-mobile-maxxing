package dev.maxxing.github.app.fonts

/** Larger files are not imported; the biggest CJK font collections are well below this. */
internal const val MAX_IMPORTED_FONT_BYTES: Long = 256L * 1024 * 1024

internal const val FONT_SIGNATURE_SIZE = 4

/** Characters kept from an imported file name, so the stored name stays well under 255 UTF-8 bytes. */
private const val MAX_IMPORTED_NAME_CHARS = 60

/**
 * Turns an imported file name (without extension) into a safe base name: runs of characters other
 * than letters, digits, `_` and `-` become `_`, and the result is shortened without splitting a
 * surrogate pair.
 */
internal fun sanitizeFontFileBase(rawBase: String): String {
    val cleaned = rawBase.replace(Regex("""[^\p{L}\p{N}_-]+"""), "_").trim('_')
    var end = minOf(cleaned.length, MAX_IMPORTED_NAME_CHARS)
    if (end < cleaned.length && end > 0 && cleaned[end - 1].isHighSurrogate()) end--
    return cleaned.substring(0, end).trim('_').ifBlank { "custom_font" }
}

/** `base.ext`, then `base_1.ext`, `base_2.ext`, ... */
internal fun importFileNameCandidates(base: String, ext: String): Sequence<String> =
    sequenceOf("$base.$ext") + generateSequence(1) { it + 1 }.map { "${base}_$it.$ext" }

/** Whether [header] starts like a TrueType, OpenType or font collection file. */
internal fun hasFontFileSignature(header: ByteArray): Boolean {
    if (header.size < FONT_SIGNATURE_SIZE) return false
    val tag = header.copyOf(FONT_SIGNATURE_SIZE)
    return FONT_SIGNATURES.any { it.contentEquals(tag) }
}

private val FONT_SIGNATURES: List<ByteArray> = listOf(
    byteArrayOf(0x00, 0x01, 0x00, 0x00), // TrueType
    "OTTO".encodeToByteArray(), // OpenType with CFF outlines
    "true".encodeToByteArray(), // Apple TrueType
    "ttcf".encodeToByteArray() // Font collection
)
