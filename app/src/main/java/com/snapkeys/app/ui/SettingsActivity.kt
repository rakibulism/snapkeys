package com.snapkeys.app.ui

import android.os.Bundle
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import com.snapkeys.app.databinding.ActivitySettingsBinding
import com.snapkeys.app.ime.KeyboardSettings

/** Keyboard options; read live by the keyboard service. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bindings = listOf(
            binding.soundSwitch to KeyboardSettings.KEY_SOUND,
            binding.vibrationSwitch to KeyboardSettings.KEY_VIBRATION,
            binding.numberRowSwitch to KeyboardSettings.KEY_NUMBER_ROW,
            binding.suggestionsSwitch to KeyboardSettings.KEY_SUGGESTIONS,
            binding.doubleSpaceSwitch to KeyboardSettings.KEY_DOUBLE_SPACE_PERIOD,
        )
        val prefs = KeyboardSettings.prefs(this)
        bindings.forEach { (switch: CompoundButton, key) ->
            switch.isChecked = prefs.getBoolean(key, true)
            switch.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
    }
}
