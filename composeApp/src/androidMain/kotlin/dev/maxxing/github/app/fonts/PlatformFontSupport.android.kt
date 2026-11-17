package dev.maxxing.github.app.fonts

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font as AndroidGraphicsFont
import android.graphics.fonts.FontFamily as AndroidGraphicsFontFamily
import android.graphics.fonts.FontStyle as AndroidGraphicsFontStyle
import android.graphics.fonts.SystemFonts
import android.graphics.text.TextRunShaper
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.util.Xml
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.maxxing.github.auth.AndroidAuthContextHolder
import dev.maxxing.github.model.CustomFontFile
import dev.maxxing.github.model.FontSelection
import dev.maxxing.github.model.FontSourceKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val LOG_TAG = "MaxxingFonts"

private val SUPPORTED_FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

private val SYSTEM_FONT_DIRS = listOf(File("/system/fonts"), File("/product/fonts"))

private const val FONTS_XML_PATH = "/system/etc/fonts.xml"

private fun isSupportedFontFile(file: File): Boolean =
    file.isFile && file.extension.lowercase() in SUPPORTED_FONT_EXTENSIONS

private fun isDirectChildOf(target: File, dir: File): Boolean =
    target.canonicalFile.parentFile == dir.canonicalFile

/** fonts.xml `lang` tags for the current default locale. */
private fun currentFontsXmlLangTags(): List<String> {
    val locale = Locale.getDefault()
    return fontsXmlLangTagsFor(locale.language, locale.script, locale.country)
}

private fun debugLog(message: () -> String) {
    // Off by default; enable with `adb shell setprop log.tag.MaxxingFonts DEBUG`.
    if (Log.isLoggable(LOG_TAG, Log.DEBUG)) Log.d(LOG_TAG, message())
}

/** System font files grouped by the family name parsed from each file name. */
private object SystemFontFiles {
    val byFamily: Map<String, List<Pair<File, SystemFontFileName>>> by lazy {
        val grouped = LinkedHashMap<String, MutableList<Pair<File, SystemFontFileName>>>()
        for (dir in SYSTEM_FONT_DIRS) {
            if (!dir.isDirectory || !dir.canRead()) continue
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (file in files.sortedBy { it.name }) {
                if (!isSupportedFontFile(file)) continue
                val parsed = parseSystemFontFileName(file.nameWithoutExtension)
                if (parsed.family.isBlank()) continue
                grouped.getOrPut(parsed.family) { mutableListOf() } += file to parsed
            }
        }
        grouped
    }
}

/** /system/etc/fonts.xml, parsed once. Null when it cannot be read or parsed. */
private object SystemFontsXml {
    val config: FontsXmlConfig? by lazy {
        try {
            File(FONTS_XML_PATH).inputStream().buffered().use { input ->
                val parser = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    setInput(input, null)
                }
                FontsXmlParser.parse(parser)
            }
        } catch (e: Exception) {
            debugLog { "Cannot parse $FONTS_XML_PATH: $e" }
            null
        }
    }

    private fun locate(fileName: String): File? {
        val direct = File(fileName)
        if (direct.isAbsolute) return direct.takeIf { it.isFile && it.canRead() }
        SYSTEM_FONT_DIRS.map { File(it, fileName) }.firstOrNull { it.isFile && it.canRead() }?.let { return it }
        // Fonts updated outside /system and /product (for example under /data/fonts) are only
        // reachable through the public SystemFonts API.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) InstalledSystemFonts.byFileName[fileName] else null
    }

    /** Resolves a generic family name such as `sans-serif` to the file fonts.xml uses for [weight]. */
    fun genericFontSpec(familyName: String, weight: Int, italic: Boolean): FontFileSpec? {
        val config = config ?: return null
        val (family, effectiveWeight) = config.findFamily(familyName, weight)
            ?: config.defaultFamilyName?.takeIf { familyName == "sans-serif" }?.let { config.findFamily(it, weight) }
            ?: return null
        val font = family.selectFont(effectiveWeight, italic) ?: return null
        val file = locate(font.fileName) ?: return null
        return FontFileSpec(file, font.ttcIndex, font.weight, font.axes, font.italic)
    }

    /** The face of a font collection that fonts.xml uses for the current locale; 0 if unknown. */
    fun localeTtcIndex(file: File): Int =
        config?.ttcIndexFor(file.name, currentFontsXmlLangTags()) ?: 0
}

/** Files of the fonts the system reports through SystemFonts (API 29+), by file name. */
@RequiresApi(Build.VERSION_CODES.Q)
private object InstalledSystemFonts {
    val byFileName: Map<String, File> by lazy {
        try {
            SystemFonts.getAvailableFonts()
                .mapNotNull { font -> font.file?.takeIf { it.isFile && it.canRead() } }
                .associateBy { it.name }
        } catch (e: Exception) {
            debugLog { "SystemFonts.getAvailableFonts failed: $e" }
            emptyMap()
        }
    }
}

/**
 * Asks the text stack which font the platform renders [familyName] with, through public APIs only
 * (API 31+). Used when fonts.xml cannot be read or does not point to a readable file.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun shapedGenericFontSpec(familyName: String, weight: Int, italic: Boolean): FontFileSpec? {
    return try {
        val paint = Paint().apply {
            typeface = Typeface.create(Typeface.create(familyName, Typeface.NORMAL), weight, italic)
            textSize = 32f
        }
        val glyphs = TextRunShaper.shapeTextRun("a", 0, 1, 0, 1, 0f, 0f, false, paint)
        if (glyphs.glyphCount() == 0) return null
        val font = glyphs.getFont(0)
        val file = font.file?.takeIf { it.isFile && it.canRead() } ?: return null
        // The wght axis is set again for the requested weight by planFontInstance.
        val axes = font.axes.orEmpty().filter { it.tag != "wght" }.associate { it.tag to it.styleValue }
        FontFileSpec(file, font.ttcIndex, font.style.weight, axes, font.style.slant == AndroidGraphicsFontStyle.FONT_SLANT_ITALIC)
    } catch (e: Exception) {
        debugLog { "Cannot shape with $familyName: $e" }
        null
    }
}

actual object PlatformFontManager {
    private fun customFontsDir(context: Context): File =
        File(context.filesDir, "custom_fonts").apply { mkdirs() }

    /** Glyph-level fallback needs Typeface.CustomFallbackBuilder (API 29); older versions ignore the fallback font. */
    actual val supportsLayeredFallback: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    actual fun listSystemFontFamilies(): List<String> = runCatching {
        SystemFontFiles.byFamily.keys.sorted()
    }.getOrDefault(emptyList())

    actual fun listImportedFonts(): List<CustomFontFile> {
        val context = runCatching { AndroidAuthContextHolder.requireContext() }.getOrNull() ?: return emptyList()
        return listImportedFonts(customFontsDir(context))
    }

    internal fun listImportedFonts(fontsDir: File): List<CustomFontFile> {
        val files = fontsDir.listFiles { file -> isSupportedFontFile(file) } ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { file ->
            CustomFontFile(
                id = file.absolutePath,
                displayName = file.nameWithoutExtension,
                filePath = file.absolutePath
            )
        }
    }

    actual fun deleteImportedFont(filePath: String): Boolean {
        val context = runCatching { AndroidAuthContextHolder.requireContext() }.getOrNull() ?: return false
        return deleteImportedFont(filePath, customFontsDir(context))
    }

    internal fun deleteImportedFont(filePath: String, fontsDir: File): Boolean {
        val target = File(filePath)
        return runCatching {
            val deleted = target.isFile && isDirectChildOf(target, fontsDir) && target.delete()
            if (deleted) retiredImportPaths += target.canonicalPath
            deleted
        }.getOrDefault(false)
    }

    /**
     * Paths of fonts deleted in this process. They are not reused for a later import, so a new file
     * never takes over a path that cached typefaces still refer to.
     */
    private val retiredImportPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Copies the document at [uri] into the app's font directory and checks that Android can load
     * it. A copy that is not a readable font is deleted and null is returned.
     */
    internal fun importFromUri(context: Context, uri: Uri): CustomFontFile? {
        val resolver = context.contentResolver
        val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "custom_font.ttf"

        val ext = rawName.substringAfterLast('.', "ttf").lowercase().let {
            if (it in SUPPORTED_FONT_EXTENSIONS) it else "ttf"
        }
        val safeBase = sanitizeFontFileBase(rawName.substringBeforeLast('.'))

        val dir = customFontsDir(context)
        val dest = importFileNameCandidates(safeBase, ext)
            .map { File(dir, it) }
            .first { !it.exists() && it.canonicalPath !in retiredImportPaths }

        val copied = try {
            resolver.openInputStream(uri)?.use { input -> copyFontStream(input, dest) } ?: false
        } catch (e: Exception) {
            false
        }
        if (!copied || !isLoadableFontFile(dest)) {
            dest.delete()
            return null
        }

        return CustomFontFile(
            id = dest.absolutePath,
            displayName = dest.nameWithoutExtension,
            filePath = dest.absolutePath
        )
    }

    /**
     * Copies [input] to [dest], stopping early when the data does not start with a font signature
     * or grows past [MAX_IMPORTED_FONT_BYTES]. Returns whether the whole font was copied.
     */
    private fun copyFontStream(input: InputStream, dest: File): Boolean {
        val header = ByteArray(FONT_SIGNATURE_SIZE)
        var headerSize = 0
        var total = 0L
        dest.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (headerSize < header.size) {
                    val count = minOf(header.size - headerSize, read)
                    System.arraycopy(buffer, 0, header, headerSize, count)
                    headerSize += count
                    if (headerSize == header.size && !hasFontFileSignature(header)) return false
                }
                total += read
                if (total > MAX_IMPORTED_FONT_BYTES) return false
                output.write(buffer, 0, read)
            }
        }
        return headerSize == header.size
    }

    /**
     * API 29+: Font.Builder must build it. API 26-28: Typeface.createFromFile must neither throw nor
     * return Typeface.DEFAULT, which some versions (at least API 28) return for a broken file.
     */
    internal fun isLoadableFontFile(file: File): Boolean {
        if (!file.isFile || !file.canRead()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadsAsRealFont(platformDefault = null) { AndroidGraphicsFont.Builder(file).build() }
        } else {
            loadsAsRealFont(platformDefault = Typeface.DEFAULT) { Typeface.createFromFile(file) }
        }
    }

    /** Picks the file of an installed family (named after its files) for [weight] and [italic]. */
    internal fun resolveSystemFontFile(familyBase: String, weight: Int, italic: Boolean = false): FontFileSpec? {
        if (familyBase.isBlank()) return null
        val candidates = SystemFontFiles.byFamily[familyBase] ?: return null
        val (file, name) = selectSystemFontFile(candidates, weight, italic) { FontFileInspector.weightAxis(it) != null }
            ?: return null
        // A collection uses the face fonts.xml assigns to the current locale (for example ja in NotoSansCJK).
        val ttcIndex = if (file.extension.equals("ttc", ignoreCase = true)) SystemFontsXml.localeTtcIndex(file) else 0
        return systemFontFileSpec(file, name, ttcIndex)
    }
}

@Composable
actual fun rememberFontFileImporter(onImported: (CustomFontFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnImported by rememberUpdatedState(onImported)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Once the copy has started, finish it and report the result even if the dialog closes.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                val imported = withContext(Dispatchers.IO) {
                    runCatching { PlatformFontManager.importFromUri(context, uri) }.getOrNull()
                }
                currentOnImported(imported)
            }
        }
    }
    return remember(launcher) {
        {
            launcher.launch(
                arrayOf(
                    "font/ttf",
                    "font/otf",
                    "font/collection",
                    "application/font-sfnt",
                    "application/x-font-ttf",
                    "application/x-font-opentype",
                    "application/octet-stream"
                )
            )
        }
    }
}

/** Weights declared for layered families. 1000 is included because bold text (font weight adjustment) can request it. */
private val LAYERED_WEIGHTS: List<Int> = (100..900 step 100).toList() + 1000

/** Identifies a selection in cache keys; an imported file also by size and time, so a replaced file is not reused. */
private fun selectionCacheKey(selection: FontSelection): String {
    val base = "${selection.kind}:${selection.value}"
    if (selection.kind != FontSourceKind.CUSTOM_FILE) return base
    val file = File(selection.value)
    return "$base:${file.length()}:${file.lastModified()}"
}

private class LayeredAndroidFont(
    private val primary: FontSelection,
    private val fallback: FontSelection,
    override val weight: FontWeight,
    override val style: FontStyle
) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = LayeredTypefaceLoader,
    variationSettings = FontVariation.Settings()
) {
    private val italic: Boolean get() = style == FontStyle.Italic

    val cacheKey: String =
        "${selectionCacheKey(primary)}|${selectionCacheKey(fallback)}|${weight.weight}|${if (style == FontStyle.Italic) "i" else "n"}"

    override fun equals(other: Any?): Boolean = other is LayeredAndroidFont && other.cacheKey == cacheKey

    override fun hashCode(): Int = cacheKey.hashCode()

    override fun toString(): String = "LayeredAndroidFont($cacheKey)"

    /** Never throws and never returns null, so a bad saved selection cannot break text layout. */
    fun buildTypeface(): Typeface {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                buildApi29Typeface()?.let { return it }
            }
            return buildLegacyTypeface()
        } catch (e: Exception) {
            debugLog { "Typeface build failed for $cacheKey: $e" }
            return Typeface.DEFAULT
        }
    }

    /**
     * The order comes from [planLayeredTypeface]: the primary (or System Default in place of an
     * unreadable primary file) first, the fallback after it, then the system fallback chain. If no
     * head can be built, this returns null and the legacy path renders the primary's generic
     * family without the fallback.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildApi29Typeface(): Typeface? {
        val w = weight.weight
        val plan = planLayeredTypeface(primary, fallback) { buildFamilyFor(it, w, italic) } ?: run {
            debugLog { "No primary file for $cacheKey; fallback ignored" }
            return null
        }
        val builder = Typeface.CustomFallbackBuilder(plan.head)
        plan.customFallback?.let { builder.addCustomFallback(it) }
        builder.setSystemFallback(plan.systemFallback)
        val slant = if (italic) AndroidGraphicsFontStyle.FONT_SLANT_ITALIC else AndroidGraphicsFontStyle.FONT_SLANT_UPRIGHT
        builder.setStyle(AndroidGraphicsFontStyle(w, slant))
        debugLog {
            "Built $cacheKey: requested=$w italic=$italic customFallback=${plan.customFallback != null} " +
                "systemFallback='${plan.systemFallback}'"
        }
        return runCatching { builder.build() }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildFamilyFor(selection: FontSelection, w: Int, italic: Boolean): AndroidGraphicsFontFamily? {
        val spec = when (selection.kind) {
            FontSourceKind.CUSTOM_FILE -> File(selection.value).takeIf { it.isFile && it.canRead() }?.let { FontFileSpec(it) }
            FontSourceKind.SYSTEM_FAMILY -> PlatformFontManager.resolveSystemFontFile(selection.value, w, italic)
            else -> genericFamilyName(selection.kind)?.let { name ->
                SystemFontsXml.genericFontSpec(name, w, italic)
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) shapedGenericFontSpec(name, w, italic) else null
            }
        } ?: return null
        return buildAndroidFontFamily(spec, w, italic)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildAndroidFontFamily(spec: FontFileSpec, w: Int, italic: Boolean): AndroidGraphicsFontFamily? {
        val plan = planFontFileInstance(spec, FontFileInspector.axes(spec.file, spec.ttcIndex), w, italic)
        return try {
            val fontBuilder = AndroidGraphicsFont.Builder(spec.file).setTtcIndex(spec.ttcIndex)
            plan.variationSettings?.let { fontBuilder.setFontVariationSettings(it) }
            plan.weight?.let { fontBuilder.setWeight(it) }
            plan.italic?.let {
                fontBuilder.setSlant(if (it) AndroidGraphicsFontStyle.FONT_SLANT_ITALIC else AndroidGraphicsFontStyle.FONT_SLANT_UPRIGHT)
            }
            val font = fontBuilder.build()
            debugLog {
                "requested=$w ${spec.file} #${spec.ttcIndex} variation=${plan.variationSettings} " +
                    "declared=${plan.weight} actual=${font.style.weight} slant=${font.style.slant} " +
                    "(below 600 with 600+ requested means fake bold)"
            }
            AndroidGraphicsFontFamily.Builder(font).build()
        } catch (e: Exception) {
            debugLog { "Cannot load ${spec.file}: $e" }
            null
        }
    }

    private fun buildLegacyTypeface(): Typeface {
        val base = when (primary.kind) {
            FontSourceKind.CUSTOM_FILE -> {
                val f = File(primary.value)
                if (f.isFile) runCatching { Typeface.createFromFile(f) }.getOrNull() else null
            }
            FontSourceKind.SYSTEM_FAMILY -> {
                val f = PlatformFontManager.resolveSystemFontFile(primary.value, weight.weight, italic)?.file
                if (f != null) runCatching { Typeface.createFromFile(f) }.getOrNull()
                else Typeface.create(primary.value, Typeface.NORMAL)
            }
            FontSourceKind.GENERIC_SERIF -> Typeface.SERIF
            FontSourceKind.GENERIC_MONOSPACE -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        } ?: Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight.weight, italic)
        } else {
            // Same threshold as Compose: 600 (SemiBold) and above is bold.
            val bold = weight.weight >= FontWeight.SemiBold.weight
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(base, style)
        }
    }
}

private object LayeredTypefaceLoader : AndroidFont.TypefaceLoader {
    private const val MAX_CACHED_TYPEFACES = 64

    /** Least recently used typefaces are dropped, so trying many fonts does not keep every file mapped. */
    private val cache = object : LinkedHashMap<String, Typeface>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean =
            size > MAX_CACHED_TYPEFACES
    }

    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
        val layered = font as? LayeredAndroidFont ?: return null
        synchronized(cache) { cache[layered.cacheKey] }?.let { return it }
        val built = layered.buildTypeface()
        return synchronized(cache) { cache.getOrPut(layered.cacheKey) { built } }
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
        withContext(Dispatchers.IO) { loadBlocking(context, font) }
}

@Composable
actual fun ProvideAppTypographyFont(
    primary: FontSelection,
    fallback: FontSelection,
    content: @Composable (FontFamily?) -> Unit
) {
    val isSimpleSingle = fallback.kind == FontSourceKind.SYSTEM_DEFAULT &&
        primary.kind in setOf(
            FontSourceKind.SYSTEM_DEFAULT,
            FontSourceKind.GENERIC_SANS,
            FontSourceKind.GENERIC_SERIF,
            FontSourceKind.GENERIC_MONOSPACE
        )

    val resolvedFamily = remember(primary, fallback, isSimpleSingle) {
        if (isSimpleSingle) {
            when (primary.kind) {
                FontSourceKind.SYSTEM_DEFAULT -> null
                FontSourceKind.GENERIC_SANS -> FontFamily.SansSerif
                FontSourceKind.GENERIC_SERIF -> FontFamily.Serif
                FontSourceKind.GENERIC_MONOSPACE -> FontFamily.Monospace
                else -> null
            }
        } else {
            // Every weight, so SemiBold (600) is not rounded to Bold, in both styles, so italic text
            // uses the real italic faces of fonts.xml families instead of a synthesized slant.
            FontFamily(
                listOf(FontStyle.Normal, FontStyle.Italic).flatMap { style ->
                    LAYERED_WEIGHTS.map { LayeredAndroidFont(primary, fallback, FontWeight(it), style) }
                }
            )
        }
    }

    content(resolvedFamily)
}
