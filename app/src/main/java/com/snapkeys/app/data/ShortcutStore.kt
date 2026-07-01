package com.snapkeys.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Lightweight persistence for [Shortcut]s backed by SharedPreferences.
 *
 * The whole list is serialized to a single JSON array. This is plenty for the
 * expected data size (hundreds of short rules) and keeps the keyboard process
 * free of a database dependency, so it starts fast.
 */
class ShortcutStore private constructor(private val prefs: SharedPreferences) {

    fun load(): List<Shortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return defaults()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { Shortcut.fromJson(array.getJSONObject(it)) }
        }.getOrElse { defaults() }
    }

    fun save(shortcuts: List<Shortcut>) {
        val array = JSONArray()
        shortcuts.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SHORTCUTS, array.toString()).apply()
    }

    fun upsert(shortcut: Shortcut, replacing: String? = null) {
        val current = load().toMutableList()
        val key = replacing ?: shortcut.trigger
        val index = current.indexOfFirst { it.trigger.equals(key, ignoreCase = true) }
        if (index >= 0) current[index] = shortcut else current.add(shortcut)
        save(current)
    }

    fun delete(trigger: String) {
        save(load().filterNot { it.trigger.equals(trigger, ignoreCase = true) })
    }

    private fun defaults(): List<Shortcut> = listOf(
        Shortcut("brb", "be right back"),
        Shortcut("omw", "on my way"),
        Shortcut("ty", "thank you"),
        Shortcut("@@", "your.email@example.com"),
    )

    companion object {
        private const val PREFS_NAME = "snapkeys_shortcuts"
        private const val KEY_SHORTCUTS = "shortcuts_json"

        fun get(context: Context): ShortcutStore {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ShortcutStore(prefs)
        }
    }
}
