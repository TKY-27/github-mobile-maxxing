package dev.maxxing.github.app.fonts

import dev.maxxing.github.model.FontSelection
import dev.maxxing.github.model.FontSourceKind
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

// Pure helpers behind the Android font support: no Context and no android.graphics, so the
// androidHostTest source set can test them on the JVM.

/** One `<font>` entry of /system/etc/fonts.xml. */
internal data class FontsXmlFont(
    val fileName: String,
    val weight: Int,
    val italic: Boolean,
    val ttcIndex: Int,
    val axes: Map<String, Float>
)

/** A named `<family>` of fonts.xml. Unnamed (language fallback) families are not kept. */
internal data class FontsXmlFamily(
    val name: String,
    val fonts: List<FontsXmlFont>
)

/** An `<alias name=... to=... weight=...>` of fonts.xml. */
internal data class FontsXmlAlias(
    val name: String,
    val to: String,
    val weight: Int?
)

/** A `<font>` of an unnamed `<family lang=...>` fallback family. */
internal data class FontsXmlLangFont(
    val langs: List<String>,
    val font: FontsXmlFont
)

internal class FontsXmlConfig(
    val families: Map<String, FontsXmlFamily>,
    val aliases: Map<String, FontsXmlAlias>,
    /** The first named family, which Android treats as the default (sans-serif). */
    val defaultFamilyName: String?,
    /** Fonts of the unnamed language fallback families, in file order. */
    val langFonts: List<FontsXmlLangFont> = emptyList()
) {
    /**
     * Resolves [name] through families and aliases. An alias with a weight overrides the requested
     * weight, as `sans-serif-medium` does. Returns the family and the weight to look up in it.
     */
    fun findFamily(name: String, weight: Int): Pair<FontsXmlFamily, Int>? {
        var current = name
        var effectiveWeight = weight
        repeat(MAX_ALIAS_HOPS) {
            (families[current] ?: families[current.lowercase(Locale.ROOT)])?.let { return it to effectiveWeight }
            val alias = aliases[current] ?: aliases[current.lowercase(Locale.ROOT)] ?: return null
            alias.weight?.let { effectiveWeight = it }
            current = alias.to
        }
        return null
    }

    /**
     * The face index of the collection [fileName] that fonts.xml uses for the first of [langTags]
     * it lists the file under (for example `ja`, `ko`, `zh-Hans`, `zh-Hant`), or null when none
     * matches. `NotoSansCJK-Regular.ttc` holds one face per language, and index 0 is not the same
     * language on every device.
     */
    fun ttcIndexFor(fileName: String, langTags: List<String>): Int? {
        val entries = langFonts.filter { it.font.fileName.substringAfterLast('/') == fileName }
        if (entries.isEmpty()) return null
        for (tag in langTags) {
            entries.firstOrNull { entry -> entry.langs.any { it.equals(tag, ignoreCase = true) } }
                ?.let { return it.font.ttcIndex }
        }
        return null
    }

    private companion object {
        const val MAX_ALIAS_HOPS = 8
    }
}

/**
 * The fonts.xml `lang` tags to try for a locale, most specific first. Chinese is told apart by
 * script: Traditional for `Hant` or Taiwan, Hong Kong and Macau, otherwise Simplified.
 */
internal fun fontsXmlLangTagsFor(language: String, script: String, country: String): List<String> {
    val lang = language.lowercase(Locale.ROOT)
    if (lang.isEmpty()) return emptyList()
    if (lang == "zh") {
        val traditional = script.equals("Hant", ignoreCase = true) ||
            (script.isEmpty() && country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO"))
        return listOf(if (traditional) "zh-Hant" else "zh-Hans")
    }
    return listOfNotNull(script.takeIf { it.isNotEmpty() }?.let { "$lang-$it" }, lang)
}

internal object FontsXmlParser {
    /** Parses fonts.xml. [parser] must already have its input set. */
    fun parse(parser: XmlPullParser): FontsXmlConfig {
        val families = LinkedHashMap<String, FontsXmlFamily>()
        val aliases = LinkedHashMap<String, FontsXmlAlias>()
        val langFonts = mutableListOf<FontsXmlLangFont>()
        var defaultFamilyName: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "family" -> {
                        val langs = parser.getAttributeValue(null, "lang")
                            ?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                        val (name, fonts) = readFamily(parser)
                        if (name != null) {
                            if (fonts.isNotEmpty() && name !in families) {
                                families[name] = FontsXmlFamily(name, fonts)
                                if (defaultFamilyName == null) defaultFamilyName = name
                            }
                        } else if (langs.isNotEmpty()) {
                            fonts.forEach { langFonts += FontsXmlLangFont(langs, it) }
                        }
                    }
                    "alias" -> {
                        val name = parser.getAttributeValue(null, "name")?.trim()
                        val to = parser.getAttributeValue(null, "to")?.trim()
                        if (!name.isNullOrEmpty() && !to.isNullOrEmpty() && name !in aliases) {
                            val weight = parser.getAttributeValue(null, "weight")?.trim()?.toIntOrNull()
                            aliases[name] = FontsXmlAlias(name, to, weight)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return FontsXmlConfig(families, aliases, defaultFamilyName, langFonts)
    }

    /** Reads one `<family>`, consuming up to its end tag. Returns its name (null if unnamed) and fonts. */
    private fun readFamily(parser: XmlPullParser): Pair<String?, List<FontsXmlFont>> {
        val name = parser.getAttributeValue(null, "name")?.trim()?.takeIf { it.isNotEmpty() }
        val depth = parser.depth
        val fonts = mutableListOf<FontsXmlFont>()
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.name == "font") {
                readFont(parser)?.let { fonts += it }
            }
        }
        return name to fonts
    }

    /**
     * Reads one `<font>`. The file name is the element's text, which may be split around `<axis>`
     * children, so all text inside the element is joined and trimmed.
     */
    private fun readFont(parser: XmlPullParser): FontsXmlFont? {
        val weight = parser.getAttributeValue(null, "weight")?.trim()?.toIntOrNull() ?: 400
        val italic = parser.getAttributeValue(null, "style")?.trim().equals("italic", ignoreCase = true)
        val ttcIndex = parser.getAttributeValue(null, "index")?.trim()?.toIntOrNull() ?: 0
        val depth = parser.depth
        val text = StringBuilder()
        val axes = LinkedHashMap<String, Float>()
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            when (event) {
                XmlPullParser.TEXT -> if (parser.depth == depth) text.append(parser.text)
                XmlPullParser.START_TAG -> if (parser.name == "axis") {
                    val tag = parser.getAttributeValue(null, "tag")?.trim()
                    val value = parser.getAttributeValue(null, "stylevalue")?.trim()?.toFloatOrNull()
                    if (tag != null && tag.length == 4 && value != null) axes[tag] = value
                }
            }
        }
        val fileName = text.toString().trim()
        if (fileName.isEmpty()) return null
        return FontsXmlFont(fileName, weight, italic, ttcIndex, axes)
    }
}

/**
 * Picks the entry of a fonts.xml family for [weight] and [italic] the way minikin does when the
 * platform renders that family itself, so a layered typeface uses the same face as plain text with
 * System Default: the lowest [minikinMatchScore] wins, and on a tie the entry listed first.
 *
 * This differs from Compose's FontMatcher ([matchFontWeight]). For example, Android 10 and 11 list
 * sans-serif as 100, 300, 400, 500, 900, 700, so SemiBold (600) is Roboto-Medium on the platform,
 * where FontMatcher would pick Roboto-Bold.
 */
internal fun FontsXmlFamily.selectFont(weight: Int, italic: Boolean = false): FontsXmlFont? =
    fonts.minByOrNull { minikinMatchScore(it.weight, it.italic, weight, italic) }

/**
 * minikin's `computeMatch` (FontFamily.cpp): 0 for an exact match, otherwise the weight distance in
 * whole hundreds (integer division) plus 2 when the slant differs.
 */
internal fun minikinMatchScore(fontWeight: Int, fontItalic: Boolean, weight: Int, italic: Boolean): Int {
    if (fontWeight == weight && fontItalic == italic) return 0
    var score = abs(fontWeight / 100 - weight / 100)
    if (fontItalic != italic) score += 2
    return score
}

/** A concrete font file and how fonts.xml (if anywhere) declares it. */
internal data class FontFileSpec(
    val file: File,
    val ttcIndex: Int = 0,
    /** Weight to declare on the Font; null lets Android read it from the file. */
    val declaredWeight: Int? = null,
    val axes: Map<String, Float> = emptyMap(),
    /** Slant to declare on the Font; null lets Android read it from the file. */
    val declaredItalic: Boolean? = null
)

/** Range of one variation axis of a font, from its fvar table. */
internal data class FontAxisRange(val min: Float, val default: Float, val max: Float)

/** How to instantiate a font file at a requested weight and slant. */
internal data class FontInstancePlan(
    /** Value for Font.Builder.setFontVariationSettings, or null to leave the default instance. */
    val variationSettings: String?,
    /** Value for Font.Builder.setWeight, or null to keep the weight read from the file. */
    val weight: Int?,
    /**
     * Slant to declare on the Font (Font.Builder.setSlant), or null to keep the slant read from the
     * file. [planFontInstance] sets it to true when it picks an italic instance through the `ital`
     * or `slnt` axis: the Font must then be declared italic, or minikin slants the already slanted
     * glyphs again. Otherwise it leaves it null; [planFontFileInstance] then fills in the slant
     * that fonts.xml or the file name declares.
     */
    val italic: Boolean? = null
)

/**
 * The `slnt` value used for italic text on a font that has a `slnt` axis but no `ital` axis: CSS
 * Fonts' default oblique angle (14 degrees), negative because `slnt` leans right below zero. It is
 * also the angle of the synthesized italic: hwui skews fake italic glyphs by -0.25
 * (MinikinSkia.cpp), about 14 degrees.
 *
 * AOSP main's minikin (FontFakery.cpp, behind the `typeface_redesign_readonly` flag) instead sets
 * `slnt` -10 and prefers `slnt` over `ital` when it adjusts a variable font itself. Here `ital`
 * comes first, because `ital` 1 selects the designed italic forms, and -14 matches the synthesized
 * slant of the released versions. In that flagged path, a font whose declared style equals the
 * requested one is used as it is when no other axes are requested (FontFamily.cpp
 * getClosestMatch); other cases have not been checked against it.
 */
internal const val ITALIC_SLNT_DEGREES = -14f

/**
 * A variable font ([weightAxis] non-null) is set to the requested weight on its `wght` axis, within
 * the axis range, and declared at that weight, so text renders at a real SemiBold or Bold. A static
 * font keeps its own weight: minikin then applies fake bold when 600 or more is requested from a
 * lighter face. Other axes from fonts.xml (for example `opsz`) are kept as they are.
 *
 * For [italic] text, a font with an `ital` axis is set to `ital` 1, unless [axes] (fonts.xml)
 * already gives `ital` a value. Without an `ital` axis, a `slnt` axis that can lean right is set to
 * [ITALIC_SLNT_DEGREES] within its range. Either way the plan declares the font italic. Otherwise
 * minikin synthesizes the slant as before.
 */
internal fun planFontInstance(
    axes: Map<String, Float>,
    weightAxis: FontAxisRange?,
    requestedWeight: Int,
    declaredWeight: Int?,
    italic: Boolean = false,
    italAxis: FontAxisRange? = null,
    slntAxis: FontAxisRange? = null
): FontInstancePlan {
    val merged = LinkedHashMap(axes)
    var weight = declaredWeight
    if (weightAxis != null) {
        val wght = requestedWeight.toFloat().coerceIn(weightAxis.min, weightAxis.max)
        merged["wght"] = wght
        weight = wght.roundToInt().coerceIn(1, 1000)
    }
    var declaredItalic: Boolean? = null
    if (italic) {
        if (italAxis != null && italAxis.max >= 1f) {
            if ("ital" !in axes) merged["ital"] = 1f
            if ((merged["ital"] ?: 0f) >= 1f) declaredItalic = true
        } else if (slntAxis != null && slntAxis.min < 0f && "slnt" !in axes) {
            merged["slnt"] = ITALIC_SLNT_DEGREES.coerceIn(slntAxis.min, minOf(slntAxis.max, 0f))
            declaredItalic = true
        }
    }
    return FontInstancePlan(
        variationSettings = merged.takeIf { it.isNotEmpty() }?.let(::formatVariationSettings),
        weight = weight,
        italic = declaredItalic
    )
}

/**
 * The instance plan for one file of a layered family, with the slant to declare resolved: the
 * file's own `wght`, `ital` and `slnt` axes ([fileAxes], from fvar) are used for [requestedWeight]
 * and [italic]. A face that is italic already ([FontFileSpec.declaredItalic]) is not leaned further
 * through `slnt`. When the plan does not pick an italic instance, the slant fonts.xml or the file
 * name declares is kept.
 */
internal fun planFontFileInstance(
    spec: FontFileSpec,
    fileAxes: Map<String, FontAxisRange>,
    requestedWeight: Int,
    italic: Boolean
): FontInstancePlan {
    val plan = planFontInstance(
        axes = spec.axes,
        weightAxis = fileAxes["wght"],
        requestedWeight = requestedWeight,
        declaredWeight = spec.declaredWeight,
        italic = italic,
        italAxis = fileAxes["ital"],
        slntAxis = fileAxes["slnt"].takeIf { spec.declaredItalic != true }
    )
    return plan.copy(italic = plan.italic ?: spec.declaredItalic)
}

/**
 * The spec of an installed system file chosen by [selectSystemFontFile]. The weight is left unset
 * so Android reads it from the file (static) or the plan sets it (variable). A file named Italic is
 * declared italic, so the plan does not lean it again through a `slnt` axis; otherwise the slant is
 * read from the file.
 */
internal fun systemFontFileSpec(file: File, name: SystemFontFileName, ttcIndex: Int): FontFileSpec =
    FontFileSpec(file, ttcIndex, declaredItalic = if (name.italic) true else null)

/**
 * Whether a font file loaded: [load] must neither throw nor return null nor return [platformDefault].
 * On API 26-28 Typeface.createFromFile can return Typeface.DEFAULT for a broken file instead of
 * throwing, so the caller passes Typeface.DEFAULT there; with a null [platformDefault] only
 * exceptions and null count as failures.
 */
internal fun <T : Any> loadsAsRealFont(platformDefault: T?, load: () -> T?): Boolean = try {
    val loaded = load()
    loaded != null && (platformDefault == null || loaded !== platformDefault)
} catch (e: Exception) {
    false
}

internal fun formatVariationSettings(axes: Map<String, Float>): String =
    axes.entries.joinToString(", ") { (tag, value) ->
        val number = if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else value.toString()
        "'$tag' $number"
    }

/** Style information parsed from a system font file name such as `NotoSerif-BoldItalic.ttf`. */
internal data class SystemFontFileName(
    val family: String,
    val weight: Int?,
    val italic: Boolean,
    /** The name carries a `-VF` token. */
    val variableHint: Boolean
)

private val WEIGHT_NAMES: Map<String, Int> = mapOf(
    "Thin" to 100,
    "Hairline" to 100,
    "ExtraLight" to 200,
    "UltraLight" to 200,
    "Light" to 300,
    "Regular" to 400,
    "Normal" to 400,
    "Book" to 400,
    "Medium" to 500,
    "SemiBold" to 600,
    "DemiBold" to 600,
    "Bold" to 700,
    "ExtraBold" to 800,
    "UltraBold" to 800,
    "Black" to 900,
    "Heavy" to 900
)

/** Trailing name tokens that carry no style and are not part of the family name (`Subsetted`, `Subsetted2`, ...). */
private val IGNORED_TOKEN = Regex("""Subsetted\d*""")

/**
 * Splits a system font file name (without extension) into family and style. Trailing `-` tokens
 * that are weights, `Italic`, weight+`Italic`, `VF` or `Subsetted`/`Subsetted2` are removed, so
 * `NotoSansAdlam-VF`, `Roboto-BoldItalic` and `NotoSansSymbols-Regular-Subsetted` map to
 * `NotoSansAdlam`, `Roboto` and `NotoSansSymbols`.
 */
internal fun parseSystemFontFileName(nameWithoutExtension: String): SystemFontFileName {
    val tokens = nameWithoutExtension.split('-').toMutableList()
    var weight: Int? = null
    var italic = false
    var variable = false
    while (tokens.size > 1) {
        val token = tokens.last()
        when {
            token == "VF" -> variable = true
            IGNORED_TOKEN.matches(token) -> Unit
            token == "Italic" -> italic = true
            token in WEIGHT_NAMES -> weight = weight ?: WEIGHT_NAMES.getValue(token)
            token.endsWith("Italic") && token.removeSuffix("Italic") in WEIGHT_NAMES -> {
                italic = true
                weight = weight ?: WEIGHT_NAMES.getValue(token.removeSuffix("Italic"))
            }
            else -> break
        }
        tokens.removeAt(tokens.lastIndex)
    }
    return SystemFontFileName(tokens.joinToString("-"), weight, italic, variable)
}

/**
 * Picks the file of a system family for [weight] and [italic]. Files of the requested slant are
 * preferred. A variable file (one with a real `wght` axis) covers every weight, so it wins when one
 * exists, a `-VF` named one first. Otherwise a static weight is chosen as Compose's FontMatcher
 * does (see [matchFontWeight]); a file without a weight in its name counts as 400. A `-VF` file
 * without a `wght` axis counts as static.
 */
internal fun selectSystemFontFile(
    candidates: List<Pair<File, SystemFontFileName>>,
    weight: Int,
    italic: Boolean = false,
    isVariable: (File) -> Boolean
): Pair<File, SystemFontFileName>? {
    val pool = candidates.filter { it.second.italic == italic }.ifEmpty { candidates }
    pool.firstOrNull { it.second.variableHint && isVariable(it.first) }?.let { return it }
    pool.firstOrNull { isVariable(it.first) }?.let { return it }
    val chosen = matchFontWeight(pool.map { it.second.weight ?: 400 }, weight) ?: return null
    return pool.first { (it.second.weight ?: 400) == chosen }
}

/** fonts.xml family name for a generic selection; System Default means `sans-serif`. */
internal fun genericFamilyName(kind: FontSourceKind): String? = when (kind) {
    FontSourceKind.SYSTEM_DEFAULT, FontSourceKind.GENERIC_SANS -> "sans-serif"
    FontSourceKind.GENERIC_SERIF -> "serif"
    FontSourceKind.GENERIC_MONOSPACE -> "monospace"
    FontSourceKind.SYSTEM_FAMILY, FontSourceKind.CUSTOM_FILE -> null
}

internal fun isFileBacked(kind: FontSourceKind): Boolean =
    kind == FontSourceKind.CUSTOM_FILE || kind == FontSourceKind.SYSTEM_FAMILY

/**
 * The order of a layered typeface: [head] is the CustomFallbackBuilder's first family,
 * [customFallback] the one added after it (if any), and [systemFallback] the system fallback chain
 * after both.
 */
internal data class LayeredTypefacePlan<F : Any>(
    val head: F,
    val customFallback: F?,
    val systemFallback: String
)

/**
 * Decides the order of a layered typeface. [build] turns a selection into a font family, or null
 * when it cannot be loaded.
 *
 * The primary always comes first and the fallback never takes precedence over it:
 * - A file-backed primary that cannot be loaded is replaced by System Default (sans-serif).
 * - If no head can be built at all, this returns null, and the caller renders the primary's
 *   generic family without the fallback. The fallback is never promoted to the head.
 * - A file-backed fallback is added after the head. A generic fallback is expressed through the
 *   system fallback chain instead, so fonts.xml's `fallbackFor` (for example serif CJK) applies.
 */
internal fun <F : Any> planLayeredTypeface(
    primary: FontSelection,
    fallback: FontSelection,
    build: (FontSelection) -> F?
): LayeredTypefacePlan<F>? {
    val head = build(primary)
        ?: (if (isFileBacked(primary.kind)) build(FontSelection.SystemDefault) else null)
        ?: return null
    val customFallback = if (isFileBacked(fallback.kind)) build(fallback) else null
    val systemFallback = genericFamilyName(fallback.kind).takeIf { fallback.kind != FontSourceKind.SYSTEM_DEFAULT }
        ?: genericFamilyName(primary.kind)
        ?: "sans-serif"
    return LayeredTypefacePlan(head, customFallback, systemFallback)
}

/** Reads the variation axes from a font file's fvar table, caching the result per file and index. */
internal object FontFileInspector {
    private val cache = ConcurrentHashMap<String, Map<String, FontAxisRange>>()

    /** The axes of the face, by tag (`wght`, `ital`, `slnt`, ...); empty for a static or unreadable font. */
    fun axes(file: File, ttcIndex: Int = 0): Map<String, FontAxisRange> {
        val key = "${file.path}#$ttcIndex:${file.length()}:${file.lastModified()}"
        return cache.getOrPut(key) { readAxes(file, ttcIndex) }
    }

    fun weightAxis(file: File, ttcIndex: Int = 0): FontAxisRange? = axes(file, ttcIndex)["wght"]

    private const val TAG_TTCF = 0x74746366 // 'ttcf'
    private const val TAG_FVAR = 0x66766172 // 'fvar'
    private const val MAX_TABLES = 1024
    private const val MAX_AXES = 64
    /** Bytes read from each fvar axis record: tag, min, default and max. */
    private const val AXIS_RECORD_READ_BYTES = 16

    fun readWeightAxis(file: File, ttcIndex: Int = 0): FontAxisRange? = readAxes(file, ttcIndex)["wght"]

    /**
     * Parses only the sfnt header, table directory and fvar axis records; empty on any problem. The
     * first record of each tag is kept, and records whose minimum exceeds their maximum are skipped.
     */
    fun readAxes(file: File, ttcIndex: Int = 0): Map<String, FontAxisRange> {
        return try {
            readAxesOrThrow(file, ttcIndex) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun tagName(tag: Int): String =
        String(charArrayOf((tag ushr 24 and 0xFF).toChar(), (tag ushr 16 and 0xFF).toChar(), (tag ushr 8 and 0xFF).toChar(), (tag and 0xFF).toChar()))

    private fun readAxesOrThrow(file: File, ttcIndex: Int): Map<String, FontAxisRange>? {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            fun seek(position: Long): Boolean {
                if (position < 0 || position >= length) return false
                raf.seek(position)
                return true
            }

            var fontOffset = 0L
            if (raf.readInt() == TAG_TTCF) {
                if (!seek(8)) return null
                val numFonts = raf.readInt()
                if (ttcIndex < 0 || ttcIndex >= numFonts) return null
                if (!seek(12L + 4L * ttcIndex)) return null
                fontOffset = raf.readInt().toLong() and 0xFFFFFFFFL
            }
            if (!seek(fontOffset + 4)) return null
            val numTables = raf.readUnsignedShort()
            if (numTables > MAX_TABLES) return null
            var fvarOffset = -1L
            for (i in 0 until numTables) {
                if (!seek(fontOffset + 12 + 16L * i)) return null
                val tag = raf.readInt()
                raf.readInt() // checksum
                val offset = raf.readInt().toLong() and 0xFFFFFFFFL
                if (tag == TAG_FVAR) {
                    fvarOffset = offset
                    break
                }
            }
            if (fvarOffset < 0 || !seek(fvarOffset)) return null
            val majorVersion = raf.readUnsignedShort()
            raf.readUnsignedShort() // minorVersion
            val axesArrayOffset = raf.readUnsignedShort()
            raf.readUnsignedShort() // reserved
            val axisCount = raf.readUnsignedShort()
            val axisSize = raf.readUnsignedShort()
            if (majorVersion != 1 || axisSize < 20 || axisCount > MAX_AXES) return null
            val axes = LinkedHashMap<String, FontAxisRange>()
            for (i in 0 until axisCount) {
                // A record that does not fit in the file ends the list; the axes before it are kept.
                val recordStart = fvarOffset + axesArrayOffset + axisSize.toLong() * i
                if (recordStart + AXIS_RECORD_READ_BYTES > length || !seek(recordStart)) break
                val tag = tagName(raf.readInt())
                val min = raf.readInt() / 65536f
                val default = raf.readInt() / 65536f
                val max = raf.readInt() / 65536f
                if (min <= max && tag !in axes) axes[tag] = FontAxisRange(min, default, max)
            }
            return axes
        }
    }
}
