package com.snapkeys.app.sync

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.snapkeys.app.data.Shortcut
import com.snapkeys.app.data.ShortcutStore
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * Orchestrates encrypted shortcut sync with the user's Google Drive.
 *
 * The whole shortcut list is one client-side-encrypted file in Drive's hidden
 * app-data folder ([DriveClient]); whichever side changed most recently wins
 * wholesale. Sign in with the same Google account and enter the same sync
 * passphrase on any device to get the same shortcuts.
 */
class SyncManager private constructor(
    private val context: Context,
    private val prefs: SharedPreferences,
) {

    val account: GoogleSignInAccount?
        get() = GoogleSignIn.getLastSignedInAccount(context)

    val isConfigured: Boolean
        get() = account != null && passphrase() != null

    fun passphrase(): String? = prefs.getString(KEY_PASSPHRASE, null)

    fun setPassphrase(value: String) {
        prefs.edit().putString(KEY_PASSPHRASE, value).apply()
    }

    fun signInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_APPDATA))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    /** Stops syncing on this device; the encrypted file stays in Drive. */
    fun signOut(onDone: () -> Unit) {
        prefs.edit().remove(KEY_PASSPHRASE).apply()
        signInClient().signOut().addOnCompleteListener { onDone() }
    }

    /**
     * Fire-and-forget background sync. [onResult] (if given) is invoked on a
     * background thread. No-op unless signed in with a passphrase set.
     */
    fun syncInBackground(onResult: ((Result<String>) -> Unit)? = null) {
        if (!isConfigured) return
        executor.execute {
            val result = runCatching { syncBlocking() }
            onResult?.invoke(result)
        }
    }

    private fun syncBlocking(): String {
        val account = account ?: error("Not signed in")
        val passphrase = passphrase() ?: error("No passphrase set")
        val token = GoogleAuthUtil.getToken(
            context, account.account!!, "oauth2:$SCOPE_APPDATA",
        )
        val drive = DriveClient(token)
        val store = ShortcutStore.get(context)
        val remote = drive.find()
        val localModifiedAt = store.modifiedAt()
        return when {
            remote == null || localModifiedAt > remote.modifiedAtMillis -> {
                val plain = toJson(store.load()).toByteArray()
                val encrypted = SyncCrypto.encrypt(plain, passphrase.toCharArray())
                val uploadedAt = drive.upload(remote?.id, encrypted)
                store.markSynced(uploadedAt)
                "Backed up to Google Drive"
            }
            remote.modifiedAtMillis > localModifiedAt -> {
                val plain = SyncCrypto.decrypt(drive.download(remote.id), passphrase.toCharArray())
                val shortcuts = fromJson(plain.decodeToString())
                store.replaceAll(shortcuts, remote.modifiedAtMillis)
                "Restored ${shortcuts.size} shortcuts from Google Drive"
            }
            else -> "Already in sync"
        }
    }

    private fun toJson(shortcuts: List<Shortcut>): String {
        val array = JSONArray()
        shortcuts.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun fromJson(raw: String): List<Shortcut> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { Shortcut.fromJson(array.getJSONObject(it)) }
    }

    companion object {
        const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private const val PREFS_NAME = "snapkeys_sync"
        private const val KEY_PASSPHRASE = "sync_passphrase"

        private val executor = Executors.newSingleThreadExecutor()

        @Volatile private var instance: SyncManager? = null

        fun get(context: Context): SyncManager = instance ?: synchronized(this) {
            instance ?: SyncManager(
                context.applicationContext,
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
