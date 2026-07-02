package com.snapkeys.app.ime

import android.content.Context
import android.content.SharedPreferences

/**
 * User-facing keyboard options, edited in the app's Settings screen and read
 * live by the keyboard. Everything defaults to on.
 */
object KeyboardSettings {

    const val KEY_SOUND = "key_sound"
    const val KEY_VIBRATION = "key_vibration"
    const val KEY_NUMBER_ROW = "number_row"
    const val KEY_SUGGESTIONS = "suggestions"
    const val KEY_DOUBLE_SPACE_PERIOD = "double_space_period"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("snapkeys_settings", Context.MODE_PRIVATE)

    fun sound(context: Context) = prefs(context).getBoolean(KEY_SOUND, true)
    fun vibration(context: Context) = prefs(context).getBoolean(KEY_VIBRATION, true)
    fun numberRow(context: Context) = prefs(context).getBoolean(KEY_NUMBER_ROW, true)
    fun suggestions(context: Context) = prefs(context).getBoolean(KEY_SUGGESTIONS, true)
    fun doubleSpacePeriod(context: Context) =
        prefs(context).getBoolean(KEY_DOUBLE_SPACE_PERIOD, true)
}
