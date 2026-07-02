package com.snapkeys.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.snapkeys.app.R
import com.snapkeys.app.data.Shortcut
import com.snapkeys.app.data.ShortcutStore
import com.snapkeys.app.databinding.ActivityMainBinding
import com.snapkeys.app.sync.SyncManager

/**
 * Home screen: shows all shortcuts, lets the user add / edit / delete them, and
 * links out to the system settings needed to enable the keyboard.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ShortcutStore
    private lateinit var adapter: ShortcutAdapter
    private lateinit var sync: SyncManager

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(::exportTo)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importFrom)
        }

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
                syncNow(silent = true)
            },
            onToggle = { shortcut, enabled ->
                store.upsert(shortcut.copy(enabled = enabled))
                refresh()
                syncNow(silent = true)
            },
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.addButton.setOnClickListener { openEditor(null) }
        binding.enableKeyboardButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        sync = SyncManager.get(this)
        binding.syncButton.setOnClickListener { onSyncButton() }
        binding.syncButton.setOnLongClickListener {
            if (sync.account != null) confirmSignOut()
            true
        }

        binding.exportButton.setOnClickListener {
            exportLauncher.launch("snapkeys-shortcuts.json")
        }
        binding.importButton.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    private fun exportTo(uri: Uri) {
        val shortcuts = store.load()
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(ShortcutStore.encode(shortcuts).toByteArray())
            } ?: error("Cannot open destination")
        }.onSuccess {
            Toast.makeText(this, getString(R.string.export_done, shortcuts.size), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importFrom(uri: Uri) {
        runCatching {
            val raw = contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: error("Cannot open file")
            ShortcutStore.decode(raw)
        }.onSuccess { imported ->
            // Merge by trigger; the imported file wins on conflicts.
            val merged = store.load().associateBy { it.trigger.lowercase() } +
                imported.associateBy { it.trigger.lowercase() }
            store.save(merged.values.toList())
            refresh()
            syncNow(silent = true)
            Toast.makeText(this, getString(R.string.import_done, imported.size), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshSyncStatus()
        // Push local edits / pull remote changes whenever the app comes up.
        syncNow(silent = true)
    }

    // region Sync

    private fun onSyncButton() {
        when {
            sync.account == null ->
                @Suppress("DEPRECATION")
                startActivityForResult(sync.signInClient().signInIntent, RC_SIGN_IN)
            sync.passphrase() == null -> promptPassphrase()
            else -> syncNow(silent = false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RC_SIGN_IN -> GoogleSignIn.getSignedInAccountFromIntent(data)
                .addOnSuccessListener {
                    refreshSyncStatus()
                    promptPassphrase()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Sign-in failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            RC_RECOVER_AUTH -> if (resultCode == RESULT_OK) syncNow(silent = false)
        }
    }

    private fun promptPassphrase() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.sync_passphrase_hint)
        }
        val container = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_passphrase_title)
            .setMessage(R.string.sync_passphrase_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val value = input.text.toString()
                if (value.isNotBlank()) {
                    sync.setPassphrase(value)
                    refreshSyncStatus()
                    syncNow(silent = false)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_sign_out_title)
            .setMessage(R.string.sync_sign_out_message)
            .setPositiveButton(R.string.sync_sign_out) { _, _ ->
                sync.signOut { runOnUiThread { refreshSyncStatus() } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun syncNow(silent: Boolean) {
        if (!sync.isConfigured) return
        sync.syncInBackground { result ->
            runOnUiThread {
                result.onSuccess { message ->
                    if (!silent) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                result.onFailure { error ->
                    if (error is UserRecoverableAuthException) {
                        @Suppress("DEPRECATION")
                        error.intent?.let { startActivityForResult(it, RC_RECOVER_AUTH) }
                    } else if (!silent) {
                        Toast.makeText(this, "Sync failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun refreshSyncStatus() {
        val account = sync.account
        when {
            account == null -> {
                binding.syncStatus.text = getString(R.string.sync_status_off)
                binding.syncButton.text = getString(R.string.sync_sign_in)
            }
            sync.passphrase() == null -> {
                binding.syncStatus.text =
                    getString(R.string.sync_status_needs_passphrase, account.email ?: "")
                binding.syncButton.text = getString(R.string.sync_set_passphrase)
            }
            else -> {
                binding.syncStatus.text = getString(R.string.sync_status_on, account.email ?: "")
                binding.syncButton.text = getString(R.string.sync_now)
            }
        }
    }

    // endregion

    private fun refresh() {
        val shortcuts: List<Shortcut> = store.load()
        adapter.submit(shortcuts)
        binding.emptyState.visibility = if (shortcuts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEditor(trigger: String?) {
        startActivity(EditShortcutActivity.intent(this, trigger))
    }

    private companion object {
        const val RC_SIGN_IN = 71
        const val RC_RECOVER_AUTH = 72
    }
}
