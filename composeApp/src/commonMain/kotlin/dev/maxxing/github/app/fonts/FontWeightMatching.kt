package dev.maxxing.github.app.fonts

/**
 * Picks the weight Compose's FontMatcher would pick from [available] for [requested], following the
 * CSS Fonts font-weight matching rules. Direction comes first, not distance:
 * - An exact weight wins.
 * - Below 400: the closest lighter weight, else the closest heavier one.
 * - Above 500: the closest heavier weight, else the closest lighter one.
 * - 400 to 500: the closest heavier weight up to 500, else the closest lighter one, else the
 *   closest weight above 500. So 400 tries 500 first, and 500 tries 400 first.
 *
 * Returns null when [available] is empty.
 */
internal fun matchFontWeight(available: Iterable<Int>, requested: Int): Int? {
    val weights = available.distinct()
    if (weights.isEmpty()) return null
    if (requested in weights) return requested

    fun closest(preferBelow: Boolean, min: Int? = null, max: Int? = null): Int? {
        val inRange = weights.filter { (min == null || it >= min) && (max == null || it <= max) }
        val below = inRange.filter { it < requested }.maxOrNull()
        val above = inRange.filter { it > requested }.minOrNull()
        return if (preferBelow) below ?: above else above ?: below
    }

    return when {
        requested < 400 -> closest(preferBelow = true)
        requested > 500 -> closest(preferBelow = false)
        else -> closest(preferBelow = false, max = 500) ?: closest(preferBelow = false, min = 500)
    }
}
