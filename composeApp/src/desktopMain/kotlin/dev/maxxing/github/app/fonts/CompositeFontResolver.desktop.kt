@file:OptIn(InternalComposeUiApi::class, ExperimentalTextApi::class)
package dev.maxxing.github.app.fonts

// Glyph-level font fallback (primary font first, a user-chosen fallback font second) needs
// Compose Multiplatform APIs marked @InternalComposeUiApi on desktop. Checked against
// CMP 1.13.0-beta02:
//
// - Skia paragraphs fall back per glyph across the family aliases of a FontLoadResult, in order.
//   Every public way to build a FontFamily (Font(file), Font(identity), FontFamily(name),
//   FontFamily(Typeface), FontFamily.SansSerif, ...) yields one alias, so a public FontFamily cannot
//   chain two fonts. A FontLoadResult with several aliases has to come from our own loader.
// - A custom loader cannot be plugged in through the public FontFamily.Resolver interface: the
//   paragraph code (resolveParagraphFontCollection) takes platformTypefacesLoader() from the
//   resolver, which only works for the resolver implementation, and throws otherwise. The only way
//   to get that implementation around our own loader is createPlatformFontFamilyResolver(), which
//   takes a PlatformTypefacesLoader. Both are @InternalComposeUiApi, as is platformTypefacesLoader(),
//   which gives us the base resolver's loader, so aliases registered here land in the same Skia
//   FontCollection that layout uses.
// - SystemFont (the font type of FontFamily(name), used as the marker) is @ExperimentalTextApi.
//   FontLoadResult itself carries no opt-in annotation but is not documented API either.
//
// These are opt-in internals, not Kotlin `internal` declarations, so no visibility suppression is
// needed. They can still change in any CMP release, so everything that touches them stays in this
// file. createCompositeFontSetup() resolves the marker once up front, directly and through the new
// resolver, and catches any Exception or LinkageError, which is how an internal API change shows up
// at runtime. On failure it returns null and the caller keeps the base resolver with the primary
// font alone. If a composite load fails later, the loader degrades to the primary font, then to
// the platform default family.

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.PlatformTypefacesLoader
import androidx.compose.ui.text.font.createPlatformFontFamilyResolver
import androidx.compose.ui.text.font.platformTypefacesLoader
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.text.platform.SystemFont

internal class CompositeFontSetup(
    val resolver: FontFamily.Resolver,
    val family: FontFamily
)

/**
 * Creates a resolver that serves [primary] first and [fallback] second, glyph by glyph, for the
 * returned marker family. Every other family goes to the base resolver's own loader unchanged.
 * Returns null if the Compose internals this relies on are unavailable or misbehave.
 */
internal fun createCompositeFontSetup(
    baseResolver: FontFamily.Resolver,
    markerIdentity: String,
    primary: FontFamily,
    fallback: FontFamily
): CompositeFontSetup? = try {
    // Without the base loader the composite could not share its FontCollection, so do not build one.
    baseResolver.platformTypefacesLoader()?.let { backend ->
        createCompositeFontSetup(backend, markerIdentity, primary, fallback)
    }
} catch (e: Exception) {
    null
} catch (e: LinkageError) {
    null
}

/** Variant that takes the backend loader directly; tests use it to avoid the UI text registry. */
internal fun createCompositeFontSetup(
    backend: PlatformTypefacesLoader,
    markerIdentity: String,
    primary: FontFamily,
    fallback: FontFamily
): CompositeFontSetup? = try {
    val loader = CompositePlatformTypefacesLoader(backend, markerIdentity, primary, fallback)
    // Resolve once now so an incompatible internal API fails here instead of during text layout:
    // directly (throws if the primary cannot be loaded), then through the resolver, which must
    // route the marker family to this loader and hand back its result unchanged.
    val direct = loader.resolveComposite(FontWeight.Normal, FontStyle.Normal)
    val resolver = createPlatformFontFamilyResolver(loader)
    val family = compositeMarkerFamily(markerIdentity)
    val viaResolver = resolver.resolve(family, FontWeight.Normal, FontStyle.Normal).value
    check(viaResolver is FontLoadResult && viaResolver.aliases == direct.aliases) {
        "Marker family was not routed to the composite loader"
    }
    CompositeFontSetup(resolver = resolver, family = family)
} catch (e: Exception) {
    null
} catch (e: LinkageError) {
    null
}

/**
 * The desktop FontFamily(name) declares a SystemFont entry for every weight from 100 to 900, in
 * both styles. A bold request therefore reaches the loader with weight 700, not 400.
 */
internal fun compositeMarkerFamily(markerIdentity: String): FontFamily = FontFamily(markerIdentity)

internal class CompositePlatformTypefacesLoader(
    private val delegate: PlatformTypefacesLoader,
    private val markerIdentity: String,
    private val primary: FontFamily,
    private val fallback: FontFamily
) : PlatformTypefacesLoader {
    override val cacheKey: Any?
        get() = delegate.cacheKey

    override val fontCollection: Any
        get() = delegate.fontCollection

    private fun isMarker(font: Font): Boolean =
        font is SystemFont && font.identity == markerIdentity

    override fun loadBlocking(font: Font): Any? =
        if (isMarker(font)) resolveCompositeSafely(font.weight, font.style) else delegate.loadBlocking(font)

    override suspend fun awaitLoad(font: Font): Any? =
        if (isMarker(font)) resolveCompositeSafely(font.weight, font.style) else delegate.awaitLoad(font)

    override fun loadPlatformTypes(
        fontFamily: FontFamily,
        fontWeight: FontWeight,
        fontStyle: FontStyle
    ): Any {
        if (fontFamily is FontListFontFamily && fontFamily.fonts.any(::isMarker)) {
            return resolveCompositeSafely(fontWeight, fontStyle)
        }
        return delegate.loadPlatformTypes(fontFamily, fontWeight, fontStyle)
    }

    /**
     * Primary aliases come first and fallback aliases after them, so Skia uses the fallback only
     * for glyphs the primary font lacks. The fallback never takes precedence.
     *
     * A generic primary (System Default, Sans-serif, ...) is a fixed list of family names, and none
     * of them may be installed. Alone, Skia would then draw with its default typeface; followed by
     * the fallback aliases, it would draw everything with the fallback font instead. So the family
     * of the typeface the generic list actually resolved to (the default typeface when none is
     * installed) is added before the fallback. File and named fonts resolve to registered aliases
     * and need nothing extra; an extra system family name there could pull in an unrelated font.
     */
    internal fun resolveComposite(weight: FontWeight, style: FontStyle): FontLoadResult {
        val p = checkNotNull(loadFamily(primary, weight, style)) { "Primary font did not resolve" }
        val f = try {
            loadFamily(fallback, weight, style)
        } catch (e: Exception) {
            null
        }
        if (f == null) return p
        val primaryAliases = if (primary is FontListFontFamily) {
            p.aliases
        } else {
            p.aliases + listOfNotNull(p.typeface?.familyName?.takeIf { it.isNotBlank() })
        }
        return FontLoadResult(
            typeface = p.typeface ?: f.typeface,
            aliases = (primaryAliases + f.aliases).distinct()
        )
    }

    private fun resolveCompositeSafely(weight: FontWeight, style: FontStyle): Any {
        try {
            return resolveComposite(weight, style)
        } catch (e: Exception) {
            // Fall through to the primary font alone.
        } catch (e: LinkageError) {
            // Fall through to the primary font alone.
        }
        try {
            loadFamily(primary, weight, style)?.let { return it }
        } catch (e: Exception) {
            // Fall through to the platform default family.
        } catch (e: LinkageError) {
            // Fall through to the platform default family.
        }
        // What the base resolver would return for the default family; passed on without a cast.
        return delegate.loadPlatformTypes(FontFamily.Default, weight, style)
    }

    private fun loadFamily(family: FontFamily, weight: FontWeight, style: FontStyle): FontLoadResult? {
        if (family is FontListFontFamily) {
            // The same choice Compose's FontMatcher makes when it resolves this family on its own.
            val sameStyle = family.fonts.filter { it.style == style }.ifEmpty { family.fonts }
            val chosenWeight = matchFontWeight(sameStyle.map { it.weight.weight }, weight.weight) ?: return null
            val chosen = sameStyle.first { it.weight.weight == chosenWeight }
            return delegate.loadBlocking(chosen) as? FontLoadResult
        }
        return delegate.loadPlatformTypes(family, weight, style) as? FontLoadResult
    }
}
