package com.snapkeys.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.snapkeys.app.data.Shortcut
import com.snapkeys.app.data.ShortcutStore
import com.snapkeys.app.databinding.ActivityMainBinding

/**
 * Home screen: shows all shortcuts, lets the user add / edit / delete them, and
 * links out to the system settings needed to enable the keyboard.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ShortcutStore
    private lateinit var adapter: ShortcutAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ShortcutStore.get(this)
        adapter = ShortcutAdapter(
            onClick = { shortcut -> openEditor(shortcut.trigger) },
            onDelete = { shortcut ->
                store.delete(shortcut.trigger)
                refresh()
            },
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.addButton.setOnClickListener { openEditor(null) }
        binding.enableKeyboardButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val shortcuts: List<Shortcut> = store.load()
        adapter.submit(shortcuts)
        binding.emptyState.visibility = if (shortcuts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEditor(trigger: String?) {
        startActivity(EditShortcutActivity.intent(this, trigger))
    }
}
