@file:OptIn(InternalComposeUiApi::class, ExperimentalTextApi::class)
package dev.maxxing.github.app

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.PlatformTypefacesLoader
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.createPlatformFontFamilyResolver
import androidx.compose.ui.text.font.platformTypefacesLoader
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.text.platform.PlatformFont
import androidx.compose.ui.text.platform.SystemFont
import dev.maxxing.github.app.fonts.PlatformFontManager
import dev.maxxing.github.app.fonts.buildCustomFileFontFamily
import dev.maxxing.github.app.fonts.compositeMarkerFamily
import dev.maxxing.github.app.fonts.createCompositeFontSetup
import dev.maxxing.github.app.fonts.hasFontFileSignature
import dev.maxxing.github.app.fonts.importFileNameCandidates
import dev.maxxing.github.app.fonts.sanitizeFontFileBase
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle as SkFontStyle
import org.jetbrains.skia.Typeface
import org.junit.Assume.assumeTrue
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle as SkTextStyle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompositeFontSkiaVerificationTest {

    /**
     * Delegate that records requests and answers with one alias per (identity, weight, style).
     * [typefaces] gives a typeface per identity; [platformTypeface] is what generic families resolve to.
     */
    private class RecordingLoader(
        private val failingIdentities: Set<String> = emptySet(),
        private val typefaces: Map<String, Typeface> = emptyMap(),
        private val platformTypeface: Typeface? = null
    ) : PlatformTypefacesLoader {
        val blockingRequests = mutableListOf<String>()
        val platformRequests = mutableListOf<FontFamily>()

        override val cacheKey: Any = Any()
        override val fontCollection: Any = Any()

        private fun aliasFor(font: Font): String {
            val identity = (font as SystemFont).identity
            if (identity in failingIdentities) error("Simulated load failure for $identity")
            return "$identity:${font.weight.weight}:${if (font.style == FontStyle.Italic) "i" else "n"}"
        }

        override fun loadBlocking(font: Font): Any {
            val alias = aliasFor(font)
            blockingRequests += alias
            return FontLoadResult(typefaces[(font as SystemFont).identity], listOf(alias))
        }

        override suspend fun awaitLoad(font: Font): Any = loadBlocking(font)

        override fun loadPlatformTypes(fontFamily: FontFamily, fontWeight: FontWeight, fontStyle: FontStyle): Any {
            platformRequests += fontFamily
            return FontLoadResult(platformTypeface, listOf("platform:$fontFamily:${fontWeight.weight}"))
        }
    }

    private fun FontFamily.Resolver.aliases(family: FontFamily, weight: FontWeight = FontWeight.Normal): List<String> =
        (resolve(family, weight).value as FontLoadResult).aliases

    private val primaryFamily = FontFamily(
        SystemFont("Primary", FontWeight.Normal),
        SystemFont("Primary", FontWeight.SemiBold),
        SystemFont("Primary", FontWeight.Bold)
    )
    private val fallbackFamily = FontFamily(SystemFont("Fallback", FontWeight.Normal))

    @Test
    fun compositeResolverPutsPrimaryAliasesBeforeFallbackAliasesAtEveryWeight() {
        val delegate = RecordingLoader()
        val setup = assertNotNull(createCompositeFontSetup(delegate, "test_marker_order", primaryFamily, fallbackFamily))

        assertEquals(listOf("Primary:400:n", "Fallback:400:n"), setup.resolver.aliases(setup.family, FontWeight.Normal))
        assertEquals(listOf("Primary:600:n", "Fallback:400:n"), setup.resolver.aliases(setup.family, FontWeight.SemiBold))
        assertEquals(listOf("Primary:700:n", "Fallback:400:n"), setup.resolver.aliases(setup.family, FontWeight.Bold))
        // The marker itself is never sent to the delegate.
        assertTrue(delegate.blockingRequests.none { it.startsWith("test_marker_order") })
    }

    @Test
    fun markerIdentityMayContainPathsAndSeparators() {
        // The app builds the identity from the raw selections, including file paths with spaces.
        val identity = "maxxing_composite|CUSTOM_FILE|/home/u/My Fonts/Latin.ttf|SYSTEM_FAMILY|Noto Sans CJK JP"
        val delegate = RecordingLoader()
        val setup = assertNotNull(createCompositeFontSetup(delegate, identity, primaryFamily, fallbackFamily))
        assertEquals(listOf("Primary:700:n", "Fallback:400:n"), setup.resolver.aliases(setup.family, FontWeight.Bold))
        assertTrue(delegate.blockingRequests.none { it.startsWith("maxxing_composite") })
    }

    @Test
    fun compositeResolverPicksThePrimaryWeightLikeComposeFontMatcher() {
        val tieFamily = FontFamily(
            SystemFont("Tie", FontWeight.ExtraLight),
            SystemFont("Tie", FontWeight.Normal),
            SystemFont("Tie", FontWeight.SemiBold)
        )
        val setup = assertNotNull(createCompositeFontSetup(RecordingLoader(), "test_marker_ties", tieFamily, fallbackFamily))
        // 500 tries 400 before anything heavier; below 400 lighter first; above 500 heavier first.
        assertEquals("Tie:400:n", setup.resolver.aliases(setup.family, FontWeight.Medium).first())
        assertEquals("Tie:200:n", setup.resolver.aliases(setup.family, FontWeight.Light).first())
        assertEquals("Tie:600:n", setup.resolver.aliases(setup.family, FontWeight.Black).first())
    }

    @Test
    fun compositePrimaryChoiceMatchesComposeResolvingThePrimaryAlone() {
        val families = listOf(
            FontFamily(SystemFont("A", FontWeight.ExtraLight), SystemFont("A", FontWeight.Normal), SystemFont("A", FontWeight.SemiBold)),
            FontFamily(SystemFont("B", FontWeight.Medium), SystemFont("B", FontWeight.Black)),
            FontFamily(SystemFont("C", FontWeight.Light), SystemFont("C", FontWeight.SemiBold)),
            FontFamily(SystemFont("D", FontWeight.Thin), SystemFont("D", FontWeight.W600), SystemFont("D", FontWeight.W900)),
            FontFamily(SystemFont("E", FontWeight.Bold), SystemFont("E", FontWeight.Normal, FontStyle.Italic))
        )
        families.forEachIndexed { index, family ->
            // Compose's own resolver, through its FontMatcher.
            val plain = createPlatformFontFamilyResolver(RecordingLoader())
            val setup = assertNotNull(createCompositeFontSetup(RecordingLoader(), "test_marker_parity_$index", family, fallbackFamily))
            // The marker family declares 100..900, so the composite loader only ever sees those weights.
            for (weight in (100..900 step 100)) {
                for (style in listOf(FontStyle.Normal, FontStyle.Italic)) {
                    val expected = (plain.resolve(family, FontWeight(weight), style).value as FontLoadResult).aliases.first()
                    val actual = (setup.resolver.resolve(setup.family, FontWeight(weight), style).value as FontLoadResult).aliases.first()
                    assertEquals(expected, actual, "family $index, weight $weight, style $style")
                }
            }
        }
    }

    @Test
    fun compositeResultKeepsThePrimaryTypeface() {
        val primaryTf = assertNotNull(FontMgr.default.legacyMakeTypeface("", SkFontStyle.NORMAL))
        val fallbackTf = assertNotNull(FontMgr.default.legacyMakeTypeface("", SkFontStyle.BOLD))
        val both = RecordingLoader(typefaces = mapOf("Primary" to primaryTf, "Fallback" to fallbackTf))
        val setup = assertNotNull(createCompositeFontSetup(both, "test_marker_typeface", primaryFamily, fallbackFamily))
        assertSame(primaryTf, (setup.resolver.resolve(setup.family).value as FontLoadResult).typeface)

        // Without a primary typeface, the fallback's is used rather than none.
        val fallbackOnly = RecordingLoader(typefaces = mapOf("Fallback" to fallbackTf))
        val setup2 = assertNotNull(createCompositeFontSetup(fallbackOnly, "test_marker_typeface2", primaryFamily, fallbackFamily))
        assertSame(fallbackTf, (setup2.resolver.resolve(setup2.family).value as FontLoadResult).typeface)
    }

    @Test
    fun genericPrimaryAddsItsResolvedFamilyBeforeTheFallback() {
        // Stands in for the typeface a generic family resolves to; with none of its names installed
        // that is Skia's default typeface.
        val resolvedTf = assertNotNull(FontMgr.default.legacyMakeTypeface("", SkFontStyle.NORMAL))
        val resolvedName = resolvedTf.familyName
        assumeTrue("default typeface has no family name", resolvedName.isNotBlank())
        val delegate = RecordingLoader(platformTypeface = resolvedTf)

        for (generic in listOf(FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace)) {
            val setup = assertNotNull(createCompositeFontSetup(delegate, "test_marker_generic_$generic", generic, fallbackFamily))
            assertEquals(
                listOf("platform:$generic:700", resolvedName, "Fallback:400:n"),
                setup.resolver.aliases(setup.family, FontWeight.Bold)
            )
        }

        // File and named fonts get nothing extra.
        val named = RecordingLoader(typefaces = mapOf("Primary" to resolvedTf))
        val setup = assertNotNull(createCompositeFontSetup(named, "test_marker_named", primaryFamily, fallbackFamily))
        assertEquals(listOf("Primary:400:n", "Fallback:400:n"), setup.resolver.aliases(setup.family))
    }

    @Test
    fun compositeResolverPassesOtherFamiliesStraightToTheDelegate() {
        val delegate = RecordingLoader()
        val setup = assertNotNull(createCompositeFontSetup(delegate, "test_marker_passthrough", primaryFamily, fallbackFamily))
        delegate.blockingRequests.clear()

        val other = FontFamily(SystemFont("Other", FontWeight.Medium))
        assertEquals(listOf("Other:500:n"), setup.resolver.aliases(other, FontWeight.Medium))
        assertEquals(listOf("Other:500:n"), delegate.blockingRequests)

        val generic = setup.resolver.aliases(FontFamily.Monospace)
        assertEquals(1, generic.size)
        assertTrue(generic.single().startsWith("platform:"))
        assertEquals(listOf<FontFamily>(FontFamily.Monospace), delegate.platformRequests)
    }

    @Test
    fun compositeResolverDegradesToPrimaryWhenFallbackFails() {
        val delegate = RecordingLoader(failingIdentities = setOf("Fallback"))
        val setup = assertNotNull(createCompositeFontSetup(delegate, "test_marker_bad_fallback", primaryFamily, fallbackFamily))
        assertEquals(listOf("Primary:700:n"), setup.resolver.aliases(setup.family, FontWeight.Bold))
    }

    @Test
    fun compositeSetupIsRejectedWhenThePrimaryCannotBeResolved() {
        val delegate = RecordingLoader(failingIdentities = setOf("Primary"))
        assertNull(createCompositeFontSetup(delegate, "test_marker_bad_primary", primaryFamily, fallbackFamily))
    }

    @Test
    fun compositeSetupIsRejectedWhenInternalApiBreaksAtRuntime() {
        val broken = object : PlatformTypefacesLoader by RecordingLoader() {
            override fun loadBlocking(font: Font): Any = throw NoSuchMethodError("simulated internal API change")
        }
        assertNull(createCompositeFontSetup(broken, "test_marker_linkage", primaryFamily, fallbackFamily))
    }

    @Test
    fun compositeFallsBackToThePrimaryWhenLoadingBreaksAfterSetup() {
        var breakFallback = false
        val delegate = object : PlatformTypefacesLoader by RecordingLoader() {
            private val inner = RecordingLoader()
            override fun loadBlocking(font: Font): Any {
                if (breakFallback && (font as SystemFont).identity == "Fallback") throw NoSuchMethodError("late break")
                return inner.loadBlocking(font)
            }
        }
        val setup = assertNotNull(createCompositeFontSetup(delegate, "test_marker_late_break", primaryFamily, fallbackFamily))
        breakFallback = true
        // A LinkageError while loading is not allowed to reach text layout.
        assertEquals(listOf("Primary:600:n"), setup.resolver.aliases(setup.family, FontWeight.SemiBold))
    }

    @Test
    fun compositeAliasesFallBackPerGlyphInRealSkiaLayout() {
        val fonts = findLatinAndCjkFontFiles()
        assumeTrue("no suitable Latin-only + CJK font files found on this machine", fonts != null)
        fonts!!
        // Go through the same entry point as the app: the Skiko resolver whose loader the composite
        // reuses. Registering the Skiko implementation is what a Compose window does; each resolver
        // gets its own loader and font collection, so the changes below stay in this test.
        registerSkikoComposeImplementation()
        val baseResolver = createFontFamilyResolver()
        val backend = assertNotNull(baseResolver.platformTypefacesLoader())
        val primary = assertNotNull(buildCustomFileFontFamily(fonts.latin)) as FontListFontFamily
        val fallback = assertNotNull(buildCustomFileFontFamily(fonts.cjk)) as FontListFontFamily
        val setup = assertNotNull(createCompositeFontSetup(baseResolver, "test_marker_skia", primary, fallback))

        val composite = setup.resolver.resolve(setup.family, FontWeight.Normal).value as FontLoadResult
        fun normalEntry(family: FontListFontFamily) = family.fonts.first { it.weight == FontWeight.Normal }
        val primaryOnly = backend.loadBlocking(normalEntry(primary)) as FontLoadResult
        val fallbackOnly = backend.loadBlocking(normalEntry(fallback)) as FontLoadResult
        assertEquals(primaryOnly.aliases + fallbackOnly.aliases, composite.aliases)

        // Use the loader's own collection with system fallback disabled, so only our aliases can supply glyphs.
        val collection = (backend.fontCollection as FontCollection).setEnableFallback(false)
        fun layout(text: String, aliases: List<String>): Pair<Float, Int> {
            val style = SkTextStyle().apply {
                fontSize = 32f
                setFontFamilies(aliases.toTypedArray())
            }
            val paragraph = ParagraphBuilder(ParagraphStyle(), collection).use { builder ->
                builder.pushStyle(style)
                builder.addText(text)
                builder.build()
            }
            paragraph.layout(10_000f)
            return (paragraph.maxIntrinsicWidth to paragraph.unresolvedCodepointsCount).also { paragraph.close() }
        }

        val cjk = "漢字かな"
        assertTrue(layout(cjk, primaryOnly.aliases).second > 0, "Primary test font unexpectedly covers CJK")
        assertEquals(0, layout(cjk, composite.aliases).second, "CJK glyphs should come from the fallback alias")

        val latin = "iiiiii WWWWWW 012345"
        val compositeLatin = layout(latin, composite.aliases).first
        assertEquals(layout(latin, primaryOnly.aliases).first, compositeLatin, 0.01f)
        assertNotEquals(layout(latin, fallbackOnly.aliases).first, compositeLatin, "Latin text must not use the fallback font")
    }

    @Test
    fun customFileFamilyRejectsInvalidFilesAndDeclaresStaticFilesOnce() {
        val tempDir = Files.createTempDirectory("maxxing-font-family-test").toFile()
        try {
            val fake = File(tempDir, "fake.ttf").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00)) }
            assertNull(buildCustomFileFontFamily(fake))
            assertNull(buildCustomFileFontFamily(File(tempDir, "missing.ttf")))

            val static = findFontFile { it.variationAxes.isNullOrEmpty() }
            assumeTrue("no static font file found on this machine", static != null)
            static!!
            val family = assertNotNull(buildCustomFileFontFamily(static)) as FontListFontFamily
            // One Normal entry: Skia emboldens it for SemiBold/Bold requests.
            assertEquals(listOf(FontWeight.Normal), family.fonts.map { it.weight })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun customVariableFileFamilyDeclaresRealWeightsThroughTheWghtAxis() {
        val variable = findFontFile { tf -> tf.variationAxes?.any { it.tag == "wght" } == true }
        assumeTrue("no variable font file with a wght axis found on this machine", variable != null)
        val axis = FontMgr.default.makeFromFile(variable!!.path)!!.variationAxes!!.first { it.tag == "wght" }
        val family = assertNotNull(buildCustomFileFontFamily(variable)) as FontListFontFamily
        assertEquals((100..900 step 100).toList(), family.fonts.map { it.weight.weight })
        family.fonts.forEach { font ->
            val setting = (font as PlatformFont).variationSettings.settings.single()
            assertEquals("wght", setting.axisName)
            // Each entry is set to its own weight, kept inside the font's axis range.
            val expected = font.weight.weight.toFloat().coerceIn(axis.minValue, axis.maxValue)
            assertEquals(expected, setting.toVariationValue(null), 0.001f)
        }
    }

    @Test
    fun markerFamilyDeclaresEveryWeightSoBoldReachesTheLoaderAsBold() {
        val family = compositeMarkerFamily("test_marker_weights") as FontListFontFamily
        assertTrue(family.fonts.all { it is SystemFont && it.identity == "test_marker_weights" })
        assertTrue(family.fonts.any { it.weight == FontWeight.SemiBold && it.style == FontStyle.Normal })
        assertTrue(family.fonts.any { it.weight == FontWeight.Bold && it.style == FontStyle.Italic })
    }

    @Test
    fun importValidatesFontsAndUsesTheGivenDirectory() {
        val tempDir = Files.createTempDirectory("maxxing-font-import-test").toFile()
        try {
            val fontsDir = File(tempDir, "fonts")
            val unsupported = File(tempDir, "ignore.txt").apply { writeText("not a font") }
            assertNull(PlatformFontManager.importFile(unsupported, fontsDir))

            val fake = File(tempDir, "Fake Font.ttf").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00)) }
            assertNull(PlatformFontManager.importFile(fake, fontsDir))
            assertTrue(fontsDir.listFiles().orEmpty().isEmpty(), "Rejected copy must be deleted")

            // A file with a font signature that Skia still cannot read is rejected before copying.
            val truncated = File(tempDir, "Truncated.ttf").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(64)) }
            assertNull(PlatformFontManager.importFile(truncated, fontsDir))
            assertTrue(fontsDir.listFiles().orEmpty().isEmpty())

            val realFont = findFontFile { true }
            assumeTrue("no readable font file found on this machine", realFont != null)
            val source = File(tempDir, "Test Custom@Font.${realFont!!.extension.lowercase()}")
            realFont.copyTo(source)

            val imported = assertNotNull(PlatformFontManager.importFile(source, fontsDir))
            assertEquals(fontsDir.canonicalFile, File(imported.filePath).canonicalFile.parentFile)
            assertEquals("Test_Custom_Font", imported.displayName)
            assertEquals(listOf(imported.filePath), PlatformFontManager.listImportedFonts(fontsDir).map { it.filePath })

            assertTrue(PlatformFontManager.deleteImportedFont(imported.filePath, fontsDir))
            assertFalse(File(imported.filePath).exists())
            assertTrue(PlatformFontManager.listImportedFonts(fontsDir).isEmpty())

            // Importing the same name again does not reuse the deleted path, which Compose's font
            // cache may still map to the old file.
            val again = assertNotNull(PlatformFontManager.importFile(source, fontsDir))
            assertNotEquals(File(imported.filePath).canonicalPath, File(again.filePath).canonicalPath)
            assertEquals("Test_Custom_Font_1", again.displayName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun deleteOnlyAcceptsFilesDirectlyInsideTheFontsDirectory() {
        val tempDir = Files.createTempDirectory("maxxing-font-delete-test").toFile()
        try {
            val fontsDir = File(tempDir, "fonts").apply { mkdirs() }
            val sibling = File(tempDir, "fonts_x").apply { mkdirs() }
            val siblingFile = File(sibling, "victim.ttf").apply { writeText("x") }
            val nested = File(fontsDir, "sub").apply { mkdirs() }
            val nestedFile = File(nested, "nested.ttf").apply { writeText("x") }
            val outside = File(tempDir, "outside.ttf").apply { writeText("x") }
            val traversal = File(fontsDir, "../outside.ttf")

            assertFalse(PlatformFontManager.deleteImportedFont(siblingFile.path, fontsDir))
            assertFalse(PlatformFontManager.deleteImportedFont(nestedFile.path, fontsDir))
            assertFalse(PlatformFontManager.deleteImportedFont(outside.path, fontsDir))
            assertFalse(PlatformFontManager.deleteImportedFont(traversal.path, fontsDir))
            assertTrue(siblingFile.exists() && nestedFile.exists() && outside.exists())

            val inside = File(fontsDir, "inside.ttf").apply { writeText("x") }
            assertTrue(PlatformFontManager.deleteImportedFont(inside.path, fontsDir))
            assertFalse(inside.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun loadableFontCheckMatchesSkia() {
        val tempDir = Files.createTempDirectory("maxxing-font-valid-test").toFile()
        try {
            val fake = File(tempDir, "fake.ttf").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00)) }
            assertFalse(PlatformFontManager.isLoadableFontFile(fake))
            assertFalse(PlatformFontManager.isLoadableFontFile(File(tempDir, "missing.ttf")))
            findFontFile { true }?.let { assertTrue(PlatformFontManager.isLoadableFontFile(it)) }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun importNamesAreSanitizedShortenedAndNumbered() {
        assertEquals("Test_Custom_Font", sanitizeFontFileBase("Test Custom@Font"))
        assertEquals("custom_font", sanitizeFontFileBase("@@@"))
        assertEquals("日本語フォント", sanitizeFontFileBase("日本語フォント"))
        val long = sanitizeFontFileBase("あ".repeat(200))
        assertEquals(60, long.length)
        assertTrue(long.encodeToByteArray().size < 255)
        // A cut never leaves half of a surrogate pair.
        val emoji = sanitizeFontFileBase("a" + "\uD835\uDC00".repeat(40))
        assertFalse(emoji.last().isHighSurrogate())
        assertEquals(listOf("F.ttf", "F_1.ttf", "F_2.ttf"), importFileNameCandidates("F", "ttf").take(3).toList())
    }

    @Test
    fun fontSignatureCheckAcceptsSfntAndCollectionsOnly() {
        assertTrue(hasFontFileSignature(byteArrayOf(0x00, 0x01, 0x00, 0x00)))
        assertTrue(hasFontFileSignature("OTTO".encodeToByteArray()))
        assertTrue(hasFontFileSignature("ttcf....".encodeToByteArray()))
        assertTrue(hasFontFileSignature("true".encodeToByteArray()))
        assertFalse(hasFontFileSignature("PK\u0003\u0004".encodeToByteArray()))
        assertFalse(hasFontFileSignature("%PDF".encodeToByteArray()))
        assertFalse(hasFontFileSignature(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun systemFontListComesFromTheFontManagerTextIsDrawnWith() {
        val families = PlatformFontManager.listSystemFontFamilies()
        assumeTrue("no system fonts on this machine", families.isNotEmpty())
        assertEquals(families.sorted(), families)
        // Every listed name is a family Skia's font manager can match, which FontFamily(name) relies on.
        families.forEach { name ->
            assertNotNull(FontMgr.default.matchFamilyStyle(name, SkFontStyle.NORMAL), "Skia does not know listed family '$name'")
        }
    }

    private data class LatinAndCjk(val latin: File, val cjk: File)

    private fun findLatinAndCjkFontFiles(): LatinAndCjk? {
        val cjkCodepoint = '漢'.code
        var latin: Pair<File, Float>? = null
        var cjk: Pair<File, Float>? = null
        for (file in candidateFontFiles()) {
            val typeface = FontMgr.default.makeFromFile(file.path) ?: continue
            val hasLatin = typeface.getUTF32Glyph('W'.code).toInt() != 0 && typeface.getUTF32Glyph('i'.code).toInt() != 0
            if (!hasLatin) continue
            val hasCjk = typeface.getUTF32Glyph(cjkCodepoint).toInt() != 0 &&
                typeface.getUTF32Glyph('か'.code).toInt() != 0 && typeface.getUTF32Glyph('字'.code).toInt() != 0
            val width = latinWidth(typeface)
            if (hasCjk) {
                if (cjk == null && (latin == null || latin.second != width)) cjk = file to width
            } else {
                if (latin == null && (cjk == null || cjk.second != width)) latin = file to width
            }
            if (latin != null && cjk != null) return LatinAndCjk(latin.first, cjk.first)
        }
        return null
    }

    private fun latinWidth(typeface: Typeface): Float =
        org.jetbrains.skia.Font(typeface, 32f).measureTextWidth("iiiiii WWWWWW 012345")

    private fun findFontFile(predicate: (Typeface) -> Boolean): File? =
        candidateFontFiles().firstOrNull { file ->
            val typeface = FontMgr.default.makeFromFile(file.path)
            typeface != null && predicate(typeface)
        }

    private fun candidateFontFiles(): Sequence<File> {
        val home = System.getProperty("user.home")
        val roots = listOf(
            "/usr/share/fonts",
            "/usr/local/share/fonts",
            "$home/.local/share/fonts",
            "$home/.fonts",
            "/System/Library/Fonts",
            "/Library/Fonts",
            "C:\\Windows\\Fonts"
        ).map(::File).filter { it.isDirectory }
        return roots.asSequence().flatMap { root ->
            root.walkTopDown()
                .maxDepth(4)
                .filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf", "ttc") }
                .sortedBy { it.path }
        }
    }
}
