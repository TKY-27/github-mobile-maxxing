package dev.maxxing.github.app.fonts

import dev.maxxing.github.model.FontSelection
import dev.maxxing.github.model.FontSourceKind
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidSystemFontConfigTest {

    // Shaped after a recent Android fonts.xml: variable Roboto with per-weight axis entries,
    // aliases with and without weights, unnamed language fallbacks and a TTC index.
    private val sampleFontsXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <familyset version="23">
            <!-- first font is default -->
            <family name="sans-serif" supportedAxes="wght,ital">
                <font weight="100" style="normal">Roboto-Regular.ttf
                    <axis tag="ital" stylevalue="0" />
                    <axis tag="wdth" stylevalue="100" />
                    <axis tag="wght" stylevalue="100" />
                </font>
                <font weight="400" style="normal">Roboto-Regular.ttf
                    <axis tag="ital" stylevalue="0" />
                    <axis tag="wdth" stylevalue="100" />
                    <axis tag="wght" stylevalue="400" />
                </font>
                <font weight="600" style="normal">Roboto-Regular.ttf
                    <axis tag="ital" stylevalue="0" />
                    <axis tag="wdth" stylevalue="100" />
                    <axis tag="wght" stylevalue="600" />
                </font>
                <font weight="700" style="normal">Roboto-Regular.ttf
                    <axis tag="ital" stylevalue="0" />
                    <axis tag="wdth" stylevalue="100" />
                    <axis tag="wght" stylevalue="700" />
                </font>
                <font weight="700" style="italic">Roboto-Regular.ttf
                    <axis tag="ital" stylevalue="1" />
                    <axis tag="wght" stylevalue="700" />
                </font>
            </family>
            <!-- Note that aliases must come after the fonts they reference. -->
            <alias name="sans-serif-thin" to="sans-serif" weight="100" />
            <alias name="sans-serif-medium" to="sans-serif" weight="500" />
            <alias name="arial" to="sans-serif" />
            <alias name="helvetica" to="arial" />
            <alias name="loop-a" to="loop-b" />
            <alias name="loop-b" to="loop-a" />
            <family name="serif">
                <font weight="400" style="normal" postScriptName="NotoSerif">NotoSerif-Regular.ttf</font>
                <font weight="700" style="normal">NotoSerif-Bold.ttf</font>
                <font weight="400" style="italic">NotoSerif-Italic.ttf</font>
            </family>
            <family name="monospace">
                <font weight="400" style="normal">DroidSansMono.ttf</font>
            </family>
            <family lang="und-Arab" variant="elegant">
                <font weight="400" style="normal">NotoNaskhArabic-Regular.ttf</font>
            </family>
            <family lang="zh-Hans">
                <font weight="400" style="normal" index="2">NotoSansCJK-Regular.ttc
                    <axis tag="wght" stylevalue="400" />
                </font>
            </family>
            <family lang="ja">
                <font weight="400" style="normal" index="1">NotoSansCJK-Regular.ttc
                    <axis tag="wght" stylevalue="400" />
                </font>
            </family>
            <family lang="zh-Hant,zh-Bopo">
                <font weight="400" style="normal" index="3">NotoSansCJK-Regular.ttc</font>
            </family>
            <family name="serif-cjk">
                <font weight="400" style="normal" index="2" fallbackFor="serif">NotoSerifCJK-Regular.ttc</font>
            </family>
            <family name="empty-family" />
        </familyset>
    """.trimIndent()

    private fun parseSample(): FontsXmlConfig {
        val parser = KXmlParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(sampleFontsXml))
        }
        return FontsXmlParser.parse(parser)
    }

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File = Files.createTempDirectory("maxxing-android-fonts").toFile().also { tempDirs += it }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun parsesNamedFamiliesFontsAxesAndIndices() {
        val config = parseSample()

        assertEquals(listOf("sans-serif", "serif", "monospace", "serif-cjk"), config.families.keys.toList())
        assertEquals("sans-serif", config.defaultFamilyName)

        val sans = config.families.getValue("sans-serif")
        assertEquals(5, sans.fonts.size)
        // Text around the <axis> children is joined and trimmed into the file name.
        assertTrue(sans.fonts.all { it.fileName == "Roboto-Regular.ttf" })
        assertEquals(mapOf("ital" to 0f, "wdth" to 100f, "wght" to 600f), sans.fonts[2].axes)
        assertEquals(600, sans.fonts[2].weight)
        assertTrue(sans.fonts[4].italic)

        val serif = config.families.getValue("serif")
        assertEquals(listOf("NotoSerif-Regular.ttf", "NotoSerif-Bold.ttf", "NotoSerif-Italic.ttf"), serif.fonts.map { it.fileName })
        assertTrue(serif.fonts.all { it.axes.isEmpty() && it.ttcIndex == 0 })

        assertEquals(2, config.families.getValue("serif-cjk").fonts.single().ttcIndex)
    }

    @Test
    fun picksTheCollectionFaceFontsXmlUsesForTheLocale() {
        val config = parseSample()
        assertEquals(1, config.ttcIndexFor("NotoSansCJK-Regular.ttc", fontsXmlLangTagsFor("ja", "", "JP")))
        assertEquals(2, config.ttcIndexFor("NotoSansCJK-Regular.ttc", fontsXmlLangTagsFor("zh", "", "CN")))
        assertEquals(3, config.ttcIndexFor("NotoSansCJK-Regular.ttc", fontsXmlLangTagsFor("zh", "", "TW")))
        assertEquals(3, config.ttcIndexFor("NotoSansCJK-Regular.ttc", fontsXmlLangTagsFor("zh", "Hant", "")))
        assertEquals(3, config.ttcIndexFor("NotoSansCJK-Regular.ttc", listOf("zh-Bopo")))
        // A language fonts.xml does not list for the file, or a file it does not list at all.
        assertNull(config.ttcIndexFor("NotoSansCJK-Regular.ttc", fontsXmlLangTagsFor("en", "", "US")))
        assertNull(config.ttcIndexFor("Unknown.ttc", fontsXmlLangTagsFor("ja", "", "JP")))
        // Named families are not language fallbacks.
        assertNull(config.ttcIndexFor("NotoSerifCJK-Regular.ttc", fontsXmlLangTagsFor("ja", "", "JP")))

        assertEquals(listOf("sr-Latn", "sr"), fontsXmlLangTagsFor("sr", "Latn", "RS"))
        assertEquals(emptyList(), fontsXmlLangTagsFor("", "", ""))
    }

    @Test
    fun fontTextIgnoresNestedElementText() {
        val xml = """
            <familyset>
                <family name="x">
                    <font weight="400">Head.ttf<axis tag="wght" stylevalue="400">junk</axis>.part</font>
                </family>
            </familyset>
        """.trimIndent()
        val parser = KXmlParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        val font = FontsXmlParser.parse(parser).families.getValue("x").fonts.single()
        assertEquals("Head.ttf.part", font.fileName)
        assertEquals(mapOf("wght" to 400f), font.axes)
    }

    @Test
    fun resolvesAliasesIncludingWeightOverridesAndChains() {
        val config = parseSample()

        val (medium, mediumWeight) = assertNotNull(config.findFamily("sans-serif-medium", 400))
        assertEquals("sans-serif", medium.name)
        assertEquals(500, mediumWeight)

        val (helvetica, helveticaWeight) = assertNotNull(config.findFamily("helvetica", 700))
        assertEquals("sans-serif", helvetica.name)
        assertEquals(700, helveticaWeight)

        assertEquals("serif", config.findFamily("SERIF", 400)?.first?.name)
        assertNull(config.findFamily("does-not-exist", 400))
        assertNull(config.findFamily("loop-a", 400))
    }

    @Test
    fun selectsFontsXmlEntriesLikeMinikin() {
        val config = parseSample()
        val sans = config.families.getValue("sans-serif")
        assertEquals(400, sans.selectFont(400)?.weight)
        assertEquals(600, sans.selectFont(600)?.weight)
        assertEquals(700, sans.selectFont(700)?.weight)
        assertFalse(sans.selectFont(700)!!.italic)
        assertTrue(sans.selectFont(700, italic = true)!!.italic)
        // Distance in whole hundreds; on a tie the entry listed first wins (400 before 600).
        assertEquals(400, sans.selectFont(500)?.weight)
        assertEquals(400, sans.selectFont(300)?.weight)
        assertEquals(100, sans.selectFont(200)?.weight)
        assertEquals(700, sans.selectFont(900)?.weight)
        assertFalse(sans.selectFont(900)!!.italic)
        // The other slant costs 2: italic 400 is the upright 400 (2), not the italic 700 (3).
        val italic400 = sans.selectFont(400, italic = true)!!
        assertEquals(400, italic400.weight)
        assertFalse(italic400.italic)

        val serif = config.families.getValue("serif")
        assertEquals("NotoSerif-Bold.ttf", serif.selectFont(600)?.fileName)
        assertEquals("NotoSerif-Regular.ttf", serif.selectFont(500)?.fileName)
        assertEquals("NotoSerif-Italic.ttf", serif.selectFont(400, italic = true)?.fileName)
        assertEquals("NotoSerif-Bold.ttf", serif.selectFont(700, italic = true)?.fileName)
    }

    @Test
    fun selectsTheFacesAndroid10And11RenderSansSerifWith() {
        // sans-serif and sans-serif-condensed as AOSP android10-release and android11-release list them.
        fun family(name: String, file: String, weights: List<Pair<Int, String>>) = FontsXmlFamily(
            name,
            weights.flatMap { (weight, style) ->
                listOf(
                    FontsXmlFont("$file-$style.ttf", weight, italic = false, ttcIndex = 0, axes = emptyMap()),
                    FontsXmlFont("$file-${if (style == "Regular") "" else style}Italic.ttf", weight, italic = true, ttcIndex = 0, axes = emptyMap())
                )
            }
        )
        val sans = family(
            "sans-serif", "Roboto",
            listOf(100 to "Thin", 300 to "Light", 400 to "Regular", 500 to "Medium", 900 to "Black", 700 to "Bold")
        )
        // SemiBold is Roboto-Medium on the platform, not Roboto-Bold.
        assertEquals("Roboto-Medium.ttf", sans.selectFont(600)?.fileName)
        assertEquals("Roboto-MediumItalic.ttf", sans.selectFont(600, italic = true)?.fileName)
        assertEquals("Roboto-Bold.ttf", sans.selectFont(700)?.fileName)
        assertEquals("Roboto-Black.ttf", sans.selectFont(800)?.fileName)
        assertEquals("Roboto-Thin.ttf", sans.selectFont(200)?.fileName)
        assertEquals("Roboto-Regular.ttf", sans.selectFont(400)?.fileName)
        assertEquals("Roboto-Italic.ttf", sans.selectFont(400, italic = true)?.fileName)

        val condensed = family(
            "sans-serif-condensed", "RobotoCondensed",
            listOf(300 to "Light", 400 to "Regular", 500 to "Medium", 700 to "Bold")
        )
        assertEquals("RobotoCondensed-Medium.ttf", condensed.selectFont(600)?.fileName)
        assertEquals("RobotoCondensed-Light.ttf", condensed.selectFont(100)?.fileName)
    }

    @Test
    fun scoresMatchesLikeMinikinComputeMatch() {
        assertEquals(0, minikinMatchScore(450, false, 450, false))
        assertEquals(0, minikinMatchScore(400, false, 450, false))
        assertEquals(1, minikinMatchScore(500, false, 600, false))
        assertEquals(1, minikinMatchScore(700, false, 600, false))
        assertEquals(2, minikinMatchScore(400, false, 400, true))
        assertEquals(3, minikinMatchScore(700, true, 600, false))
    }

    @Test
    fun plansRealWeightsForVariableFontsAndKeepsStaticWeights() {
        val axes = mapOf("ital" to 0f, "wdth" to 100f, "wght" to 400f)
        val variable = planFontInstance(axes, FontAxisRange(100f, 400f, 900f), requestedWeight = 600, declaredWeight = 400)
        assertEquals("'ital' 0, 'wdth' 100, 'wght' 600", variable.variationSettings)
        assertEquals(600, variable.weight)

        val clamped = planFontInstance(emptyMap(), FontAxisRange(300f, 400f, 700f), requestedWeight = 900, declaredWeight = null)
        assertEquals("'wght' 700", clamped.variationSettings)
        assertEquals(700, clamped.weight)

        // Static file: weight comes from the file (or fonts.xml), so minikin can fake bold.
        assertEquals(FontInstancePlan(null, null), planFontInstance(emptyMap(), null, 600, null))
        assertEquals(FontInstancePlan(null, 700), planFontInstance(emptyMap(), null, 600, 700))
        assertEquals("'opsz' 14.5", planFontInstance(mapOf("opsz" to 14.5f), null, 400, 400).variationSettings)
    }

    @Test
    fun matchesWeightsLikeComposeFontMatcher() {
        assertNull(matchFontWeight(emptyList(), 400))
        assertEquals(700, matchFontWeight(listOf(400, 700), 700))
        // Below 400: lighter first, then heavier.
        assertEquals(100, matchFontWeight(listOf(100, 400), 300))
        assertEquals(400, matchFontWeight(listOf(400, 700), 300))
        // Above 500: heavier first, then lighter.
        assertEquals(900, matchFontWeight(listOf(500, 900), 600))
        assertEquals(500, matchFontWeight(listOf(300, 500), 600))
        // 400: up to 500 first, then lighter, then above 500.
        assertEquals(500, matchFontWeight(listOf(300, 500, 600), 400))
        assertEquals(300, matchFontWeight(listOf(300, 600), 400))
        assertEquals(600, matchFontWeight(listOf(600, 900), 400))
        // 500: 400 first (nothing between), then lighter, then heavier.
        assertEquals(400, matchFontWeight(listOf(400, 600), 500))
        assertEquals(300, matchFontWeight(listOf(300, 600), 500))
        assertEquals(600, matchFontWeight(listOf(600, 900), 500))
        // Between 400 and 500.
        assertEquals(500, matchFontWeight(listOf(400, 500), 450))
    }

    @Test
    fun plansItalicInstancesThroughTheItalOrSlntAxis() {
        val wght = FontAxisRange(100f, 400f, 900f)
        val ital = FontAxisRange(0f, 0f, 1f)

        val italic = planFontInstance(emptyMap(), wght, 700, null, italic = true, italAxis = ital)
        assertEquals("'wght' 700, 'ital' 1", italic.variationSettings)
        assertEquals(true, italic.italic)

        // Upright text leaves ital alone and declares nothing.
        val upright = planFontInstance(emptyMap(), wght, 400, null, italic = false, italAxis = ital)
        assertEquals("'wght' 400", upright.variationSettings)
        assertNull(upright.italic)

        // A value from fonts.xml is kept; only an italic value declares the font italic.
        val fromXml = planFontInstance(mapOf("ital" to 1f), wght, 700, 700, italic = true, italAxis = ital)
        assertEquals("'ital' 1, 'wght' 700", fromXml.variationSettings)
        assertEquals(true, fromXml.italic)
        val uprightXml = planFontInstance(mapOf("ital" to 0f), wght, 700, 700, italic = true, italAxis = ital)
        assertEquals("'ital' 0, 'wght' 700", uprightXml.variationSettings)
        assertNull(uprightXml.italic)

        // Without ital, slnt leans right by CSS's default oblique angle, within the axis range.
        val slnt = planFontInstance(emptyMap(), null, 400, null, italic = true, slntAxis = FontAxisRange(-20f, 0f, 0f))
        assertEquals("'slnt' -14", slnt.variationSettings)
        assertEquals(true, slnt.italic)
        assertNull(slnt.weight)
        val shallow = planFontInstance(emptyMap(), null, 400, null, italic = true, slntAxis = FontAxisRange(-10f, 0f, 0f))
        assertEquals("'slnt' -10", shallow.variationSettings)
        // ital wins over slnt when both exist.
        val both = planFontInstance(emptyMap(), null, 400, null, italic = true, italAxis = ital, slntAxis = FontAxisRange(-10f, 0f, 0f))
        assertEquals("'ital' 1", both.variationSettings)
        // A slnt axis that cannot lean right is not used; minikin synthesizes the slant instead.
        val leftOnly = planFontInstance(emptyMap(), null, 400, null, italic = true, slntAxis = FontAxisRange(0f, 0f, 10f))
        assertEquals(FontInstancePlan(null, null, null), leftOnly)
        // A slnt value that fonts.xml (or the shaped platform font) already gives is kept, and the
        // plan does not declare the font italic for it.
        val presetSlnt = planFontInstance(mapOf("slnt" to -5f), null, 400, null, italic = true, slntAxis = FontAxisRange(-20f, 0f, 0f))
        assertEquals("'slnt' -5", presetSlnt.variationSettings)
        assertNull(presetSlnt.italic)
    }

    @Test
    fun plansFontFilesFromTheirOwnAxesAndDeclaredSlant() {
        val file = File("/fonts/Test.ttf")
        val wght = FontAxisRange(100f, 400f, 900f)
        val ital = FontAxisRange(0f, 0f, 1f)
        val slnt = FontAxisRange(-15f, 0f, 0f)

        // Each file axis feeds its own part of the plan.
        val italFile = planFontFileInstance(FontFileSpec(file), mapOf("wght" to wght, "ital" to ital), 700, italic = true)
        assertEquals(FontInstancePlan("'wght' 700, 'ital' 1", 700, true), italFile)
        val slntFile = planFontFileInstance(FontFileSpec(file), mapOf("slnt" to slnt), 400, italic = true)
        assertEquals(FontInstancePlan("'slnt' -14", null, true), slntFile)
        // A slnt axis is not mistaken for ital: an upright request leaves it alone.
        assertEquals(FontInstancePlan(null, null, null), planFontFileInstance(FontFileSpec(file), mapOf("slnt" to slnt), 400, italic = false))

        // A face declared italic is not leaned again, and stays declared italic.
        val italicFace = FontFileSpec(file, declaredItalic = true)
        assertEquals(FontInstancePlan(null, null, true), planFontFileInstance(italicFace, mapOf("slnt" to slnt), 400, italic = true))

        // Without an italic instance, the slant from fonts.xml is declared as it is.
        val xmlUpright = FontFileSpec(file, declaredWeight = 400, axes = mapOf("ital" to 0f), declaredItalic = false)
        assertEquals(FontInstancePlan("'ital' 0", 400, false), planFontFileInstance(xmlUpright, mapOf("ital" to ital), 400, italic = true))
        val xmlItalic = FontFileSpec(file, declaredWeight = 700, declaredItalic = true)
        assertEquals(FontInstancePlan(null, 700, true), planFontFileInstance(xmlItalic, emptyMap(), 700, italic = true))
        // A plain imported file declares nothing, so Android reads weight and slant from it.
        assertEquals(FontInstancePlan(null, null, null), planFontFileInstance(FontFileSpec(file), emptyMap(), 600, italic = true))
    }

    @Test
    fun systemFontFileSpecsDeclareOnlyItalicFileNames() {
        val file = File("/system/fonts/NotoSerif-BoldItalic.ttf")
        val italic = systemFontFileSpec(file, parseSystemFontFileName("NotoSerif-BoldItalic"), ttcIndex = 0)
        assertEquals(FontFileSpec(file, 0, declaredWeight = null, declaredItalic = true), italic)
        val upright = systemFontFileSpec(File("/system/fonts/NotoSansCJK-Regular.ttc"), parseSystemFontFileName("NotoSansCJK-Regular"), ttcIndex = 2)
        assertEquals(2, upright.ttcIndex)
        assertNull(upright.declaredItalic)
        assertNull(upright.declaredWeight)
    }

    @Test
    fun loadCheckRejectsExceptionsNullAndThePlatformDefault() {
        val platformDefault = Any()
        assertTrue(loadsAsRealFont(platformDefault) { Any() })
        assertFalse(loadsAsRealFont(platformDefault) { platformDefault })
        assertFalse(loadsAsRealFont<Any>(platformDefault) { null })
        assertFalse(loadsAsRealFont<Any>(platformDefault) { throw RuntimeException("Font not found") })
        // Without a default to compare with, only exceptions and null fail.
        assertTrue(loadsAsRealFont<Any>(null) { platformDefault })
        assertFalse(loadsAsRealFont<Any>(null) { throw IllegalArgumentException("bad font") })
    }

    @Test
    fun parsesSystemFontFileNames() {
        assertEquals(SystemFontFileName("NotoSansAdlam", null, false, true), parseSystemFontFileName("NotoSansAdlam-VF"))
        assertEquals(SystemFontFileName("Roboto", 400, false, false), parseSystemFontFileName("Roboto-Regular"))
        assertEquals(SystemFontFileName("Roboto", 700, true, false), parseSystemFontFileName("Roboto-BoldItalic"))
        assertEquals(SystemFontFileName("NotoSerif", 600, true, false), parseSystemFontFileName("NotoSerif-SemiBoldItalic"))
        assertEquals(SystemFontFileName("NotoSerif", null, true, true), parseSystemFontFileName("NotoSerif-Italic-VF"))
        assertEquals(SystemFontFileName("NotoSansSymbols", 400, false, false), parseSystemFontFileName("NotoSansSymbols-Regular-Subsetted"))
        assertEquals(SystemFontFileName("NotoSansSymbols", 400, false, false), parseSystemFontFileName("NotoSansSymbols-Regular-Subsetted2"))
        assertEquals(SystemFontFileName("NotoSansCJK", 400, false, false), parseSystemFontFileName("NotoSansCJK-Regular"))
        assertEquals(SystemFontFileName("DroidSansMono", null, false, false), parseSystemFontFileName("DroidSansMono"))
        assertEquals(SystemFontFileName("Some-Family", 900, false, false), parseSystemFontFileName("Some-Family-Black"))
        // A lone style token is kept as the family name.
        assertEquals("Bold", parseSystemFontFileName("Bold").family)
    }

    @Test
    fun selectsSystemFontFilesByWeightAndPrefersVariableFiles() {
        fun candidate(name: String) = File("/system/fonts/$name.ttf") to parseSystemFontFileName(name)
        // Sorted by name, as the font directory listing is, so Italic files come before Regular ones.
        val serif = listOf(
            candidate("NotoSerif-Bold"),
            candidate("NotoSerif-BoldItalic"),
            candidate("NotoSerif-Italic"),
            candidate("NotoSerif-Regular")
        )
        val noVariable: (File) -> Boolean = { false }
        fun pick(pool: List<Pair<File, SystemFontFileName>>, weight: Int, italic: Boolean = false, isVariable: (File) -> Boolean = noVariable) =
            selectSystemFontFile(pool, weight, italic, isVariable)?.first?.nameWithoutExtension
        assertEquals("NotoSerif-Regular", pick(serif, 400))
        assertEquals("NotoSerif-Bold", pick(serif, 600))
        assertEquals("NotoSerif-Bold", pick(serif, 700))
        assertEquals("NotoSerif-Italic", pick(serif, 400, italic = true))
        assertEquals("NotoSerif-BoldItalic", pick(serif, 700, italic = true))

        // Compose's FontMatcher order: 500 takes Regular (400) before SemiBold (600), 300 takes ExtraLight (200).
        val ties = listOf(candidate("Tie-ExtraLight"), candidate("Tie-Regular"), candidate("Tie-SemiBold"))
        assertEquals("Tie-Regular", pick(ties, 500))
        assertEquals("Tie-ExtraLight", pick(ties, 300))
        // Direction before distance: 600 takes Black (900) over Medium (500).
        val wide = listOf(candidate("Wide-Medium"), candidate("Wide-Black"))
        assertEquals("Wide-Black", pick(wide, 600))
        assertEquals("Wide-Medium", pick(wide, 400))

        // A -VF file wins only when it really has a wght axis; otherwise it counts as a static Regular.
        val withVf = serif + candidate("NotoSerif-VF")
        assertEquals("NotoSerif-Bold", pick(withVf, 700))
        assertEquals("NotoSerif-VF", pick(withVf, 700) { it.name == "NotoSerif-VF.ttf" })
        // A -VF file is preferred over another variable file.
        assertEquals("NotoSerif-VF", pick(withVf, 700) { true })

        // A variable file without -VF in its name is found through the fvar check.
        val roboto = listOf(candidate("Roboto-Italic"), candidate("Roboto-Regular"))
        assertEquals("Roboto-Regular", pick(roboto, 700) { it.name == "Roboto-Regular.ttf" })
        // Static Italic and Regular only: upright text never gets the Italic file.
        assertEquals("Roboto-Regular", pick(roboto, 400))
        assertEquals("Roboto-Regular", pick(roboto, 700))
        // Only italic files: they are used rather than nothing.
        assertEquals("Roboto-Italic", pick(listOf(candidate("Roboto-Italic")), 400))

        assertNull(selectSystemFontFile(emptyList(), 400, isVariable = noVariable))
    }

    /** Builds "families" as labelled strings, failing the selections in [unloadable]. */
    private fun plan(
        primary: FontSelection,
        fallback: FontSelection,
        unloadable: Set<FontSelection> = emptySet()
    ): LayeredTypefacePlan<String>? = planLayeredTypeface(primary, fallback) { selection ->
        if (selection in unloadable) null else "${selection.kind}:${selection.value}"
    }

    @Test
    fun layeredPlanAlwaysPutsThePrimaryFirst() {
        val customPrimary = FontSelection(FontSourceKind.CUSTOM_FILE, "/fonts/Latin.ttf")
        val customFallback = FontSelection(FontSourceKind.CUSTOM_FILE, "/fonts/Kana.ttf")
        val systemFallback = FontSelection(FontSourceKind.SYSTEM_FAMILY, "NotoSerifCJK")
        val generics = listOf(FontSelection.SystemDefault, FontSelection.SansSerif, FontSelection.Serif, FontSelection.Monospace)
        val fileBacked = listOf(customFallback, systemFallback)

        for (primary in generics + customPrimary + systemFallback.copy(value = "Roboto")) {
            for (fallback in generics + fileBacked) {
                val result = assertNotNull(plan(primary, fallback), "$primary / $fallback")
                assertEquals("${primary.kind}:${primary.value}", result.head, "head for $primary / $fallback")
                if (fallback in fileBacked) {
                    assertEquals("${fallback.kind}:${fallback.value}", result.customFallback)
                } else {
                    assertNull(result.customFallback)
                }
            }
        }
    }

    @Test
    fun layeredPlanChoosesTheSystemFallbackChain() {
        val custom = FontSelection(FontSourceKind.CUSTOM_FILE, "/fonts/Latin.ttf")
        // A generic fallback becomes the system chain, so fonts.xml's serif CJK fallbacks apply.
        assertEquals("serif", plan(FontSelection.SansSerif, FontSelection.Serif)?.systemFallback)
        assertEquals("monospace", plan(custom, FontSelection.Monospace)?.systemFallback)
        // Otherwise the primary's generic family, or sans-serif.
        assertEquals("serif", plan(FontSelection.Serif, FontSelection.SystemDefault)?.systemFallback)
        assertEquals("serif", plan(FontSelection.Serif, FontSelection(FontSourceKind.CUSTOM_FILE, "/f.ttf"))?.systemFallback)
        assertEquals("sans-serif", plan(custom, FontSelection.SystemDefault)?.systemFallback)
        assertEquals("sans-serif", plan(FontSelection.SystemDefault, FontSelection.SystemDefault)?.systemFallback)
    }

    @Test
    fun layeredPlanNeverPromotesTheFallback() {
        val customPrimary = FontSelection(FontSourceKind.CUSTOM_FILE, "/fonts/Broken.ttf")
        val systemPrimary = FontSelection(FontSourceKind.SYSTEM_FAMILY, "Missing")
        val fallback = FontSelection(FontSourceKind.CUSTOM_FILE, "/fonts/Kana.ttf")

        // An unreadable file-backed primary is replaced by System Default, still ahead of the fallback.
        for (primary in listOf(customPrimary, systemPrimary)) {
            val result = assertNotNull(plan(primary, fallback, unloadable = setOf(primary)))
            assertEquals("SYSTEM_DEFAULT:", result.head)
            assertEquals("CUSTOM_FILE:/fonts/Kana.ttf", result.customFallback)
        }

        // A generic primary that cannot be resolved gives no plan; the fallback is not used as the head.
        for (primary in listOf(FontSelection.SystemDefault, FontSelection.SansSerif, FontSelection.Serif, FontSelection.Monospace)) {
            assertNull(plan(primary, fallback, unloadable = setOf(primary)))
        }
        // Nor when the System Default substitute cannot be resolved either.
        assertNull(plan(customPrimary, fallback, unloadable = setOf(customPrimary, FontSelection.SystemDefault)))

        // An unreadable fallback is simply left out.
        val result = assertNotNull(plan(FontSelection.Serif, fallback, unloadable = setOf(fallback)))
        assertEquals("GENERIC_SERIF:", result.head)
        assertNull(result.customFallback)
    }

    @Test
    fun readsWeightAxisFromFvarTables() {
        val dir = tempDir()
        val variable = File(dir, "variable.ttf").apply {
            writeBytes(sfnt(listOf(Axis("wdth", 75f, 100f, 100f), Axis("wght", 100f, 400f, 900f))))
        }
        assertEquals(FontAxisRange(100f, 400f, 900f), FontFileInspector.readWeightAxis(variable))

        val widthOnly = File(dir, "width-only.ttf").apply { writeBytes(sfnt(listOf(Axis("wdth", 75f, 100f, 100f)))) }
        assertNull(FontFileInspector.readWeightAxis(widthOnly))

        val static = File(dir, "static.ttf").apply { writeBytes(sfnt(null)) }
        assertNull(FontFileInspector.readWeightAxis(static))

        val fake = File(dir, "fake.ttf").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00)) }
        assertNull(FontFileInspector.readWeightAxis(fake))
        assertNull(FontFileInspector.readWeightAxis(File(dir, "missing.ttf")))

        val collection = File(dir, "collection.ttc").apply {
            writeBytes(ttc(listOf(null, listOf(Axis("wght", 200f, 400f, 800f)))))
        }
        assertNull(FontFileInspector.readWeightAxis(collection, ttcIndex = 0))
        assertEquals(FontAxisRange(200f, 400f, 800f), FontFileInspector.readWeightAxis(collection, ttcIndex = 1))
        assertNull(FontFileInspector.readWeightAxis(collection, ttcIndex = 2))

        // The cached lookup returns the same answers.
        assertEquals(FontAxisRange(100f, 400f, 900f), FontFileInspector.weightAxis(variable))
        assertNull(FontFileInspector.weightAxis(static))

        // Every axis is read by tag, including negative ranges.
        val slanted = File(dir, "slanted.ttf").apply {
            writeBytes(sfnt(listOf(Axis("wght", 100f, 400f, 900f), Axis("ital", 0f, 0f, 1f), Axis("slnt", -10f, 0f, 0f))))
        }
        assertEquals(
            mapOf(
                "wght" to FontAxisRange(100f, 400f, 900f),
                "ital" to FontAxisRange(0f, 0f, 1f),
                "slnt" to FontAxisRange(-10f, 0f, 0f)
            ),
            FontFileInspector.axes(slanted)
        )
        assertEquals(emptyMap(), FontFileInspector.axes(static))
        assertEquals(emptyMap(), FontFileInspector.readAxes(fake))
    }

    @Test
    fun keepsTheAxesBeforeARecordThatIsCutOff() {
        val dir = tempDir()
        val full = sfnt(listOf(Axis("wght", 100f, 400f, 900f), Axis("ital", 0f, 0f, 1f)))
        val wghtOnly = mapOf("wght" to FontAxisRange(100f, 400f, 900f))
        // The second 20-byte record starts 20 bytes before the end of the file.
        val secondRecord = full.size - 20
        val atBoundary = File(dir, "boundary.ttf").apply { writeBytes(full.copyOf(secondRecord)) }
        assertEquals(wghtOnly, FontFileInspector.readAxes(atBoundary))
        // Cut after the tag and minimum of the second record.
        val midRecord = File(dir, "mid.ttf").apply { writeBytes(full.copyOf(secondRecord + 8)) }
        assertEquals(wghtOnly, FontFileInspector.readAxes(midRecord))
        // The 16 bytes read per record are enough; the trailing flags and name ID are not read.
        val noFlags = File(dir, "noflags.ttf").apply { writeBytes(full.copyOf(secondRecord + 16)) }
        assertEquals(2, FontFileInspector.readAxes(noFlags).size)
    }

    private data class Axis(val tag: String, val min: Float, val default: Float, val max: Float)

    /** A minimal single-font sfnt: a dummy 'head' table, plus an 'fvar' table when [axes] is non-null. */
    private fun sfnt(axes: List<Axis>?): ByteArray = ttcFontAt(0, axes).array()

    private fun ttc(fonts: List<List<Axis>?>): ByteArray {
        val headerSize = 12 + 4 * fonts.size
        val bodies = mutableListOf<ByteArray>()
        val offsets = mutableListOf<Int>()
        var cursor = headerSize
        for (axes in fonts) {
            offsets += cursor
            val body = ttcFontAt(cursor, axes).array()
            bodies += body
            cursor += body.size
        }
        val out = ByteBuffer.allocate(cursor)
        out.put("ttcf".toByteArray()).putInt(0x00010000).putInt(fonts.size)
        offsets.forEach { out.putInt(it) }
        bodies.forEach { out.put(it) }
        return out.array()
    }

    /** Builds one font whose table offsets are absolute, assuming it starts at [base] in the file. */
    private fun ttcFontAt(base: Int, axes: List<Axis>?): ByteBuffer {
        val numTables = if (axes == null) 1 else 2
        val directorySize = 12 + 16 * numTables
        val headSize = 8
        val fvarSize = if (axes == null) 0 else 16 + 20 * axes.size
        val buffer = ByteBuffer.allocate(directorySize + headSize + fvarSize)
        buffer.putInt(0x00010000).putShort(numTables.toShort()).putShort(0).putShort(0).putShort(0)
        val headOffset = base + directorySize
        buffer.put("head".toByteArray()).putInt(0).putInt(headOffset).putInt(headSize)
        if (axes != null) {
            buffer.put("fvar".toByteArray()).putInt(0).putInt(headOffset + headSize).putInt(fvarSize)
        }
        buffer.put(ByteArray(headSize))
        if (axes != null) {
            buffer.putShort(1).putShort(0).putShort(16).putShort(2)
            buffer.putShort(axes.size.toShort()).putShort(20).putShort(0).putShort((4 + 4 * axes.size).toShort())
            for (axis in axes) {
                buffer.put(axis.tag.toByteArray())
                    .putInt((axis.min * 65536).toInt())
                    .putInt((axis.default * 65536).toInt())
                    .putInt((axis.max * 65536).toInt())
                    .putShort(0)
                    .putShort(256)
            }
        }
        return buffer
    }
}
