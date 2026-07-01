package com.snapkeys.app.data

import org.json.JSONObject

/**
 * A single text-expansion rule.
 *
 * When the user types [trigger] followed by a delimiter (space, newline or
 * punctuation), the keyboard replaces the trigger with [expansion].
 *
 * Example: trigger = "brb", expansion = "be right back".
 */
data class Shortcut(
    val trigger: String,
    val expansion: String,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_TRIGGER, trigger)
        put(KEY_EXPANSION, expansion)
        put(KEY_ENABLED, enabled)
    }

    companion object {
        private const val KEY_TRIGGER = "trigger"
        private const val KEY_EXPANSION = "expansion"
        private const val KEY_ENABLED = "enabled"

        fun fromJson(json: JSONObject): Shortcut = Shortcut(
            trigger = json.getString(KEY_TRIGGER),
            expansion = json.getString(KEY_EXPANSION),
            enabled = json.optBoolean(KEY_ENABLED, true),
        )
    }
}
