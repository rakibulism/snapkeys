package com.snapkeys.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.snapkeys.app.data.Shortcut
import com.snapkeys.app.data.ShortcutStore
import com.snapkeys.app.databinding.ActivityEditShortcutBinding

/** Create a new shortcut or edit an existing one (identified by its trigger). */
class EditShortcutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditShortcutBinding
    private lateinit var store: ShortcutStore
    private var originalTrigger: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditShortcutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ShortcutStore.get(this)
        originalTrigger = intent.getStringExtra(EXTRA_TRIGGER)

        originalTrigger?.let { trigger ->
            store.load().firstOrNull { it.trigger == trigger }?.let { existing ->
                binding.triggerInput.setText(existing.trigger)
                binding.expansionInput.setText(existing.expansion)
            }
        }

        binding.saveButton.setOnClickListener { save() }
    }

    private fun save() {
        val trigger = binding.triggerInput.text.toString().trim()
        val expansion = binding.expansionInput.text.toString()

        if (trigger.isEmpty()) {
            binding.triggerInput.error = getString(com.snapkeys.app.R.string.error_trigger_required)
            return
        }
        if (expansion.isEmpty()) {
            binding.expansionInput.error = getString(com.snapkeys.app.R.string.error_expansion_required)
            return
        }

        store.upsert(Shortcut(trigger, expansion), replacing = originalTrigger)
        Toast.makeText(this, com.snapkeys.app.R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val EXTRA_TRIGGER = "extra_trigger"

        fun intent(context: Context, trigger: String?): Intent =
            Intent(context, EditShortcutActivity::class.java).apply {
                putExtra(EXTRA_TRIGGER, trigger)
            }
    }
}
