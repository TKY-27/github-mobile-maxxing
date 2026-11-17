package dev.maxxing.github.auth

import kotlinx.serialization.json.Json

/**
 * The Json settings the platform token stores read and write saved data with.
 *
 * coerceInputValues: a value this version does not know (such as a font kind added by a newer
 * version) falls back to the field default instead of resetting all saved preferences.
 */
internal val TokenStoreJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
