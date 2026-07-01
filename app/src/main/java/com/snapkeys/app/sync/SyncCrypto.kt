package com.snapkeys.app.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side encryption for the synced shortcut list.
 *
 * AES-256-GCM with the key derived from the user's sync passphrase via
 * PBKDF2-HmacSHA256. Encryption happens on-device before upload, so Google
 * Drive only ever stores an opaque blob; the same passphrase on another
 * device derives the same key and decrypts it.
 *
 * Blob layout: "SKE1" magic | 16-byte salt | 12-byte IV | GCM ciphertext.
 */
object SyncCrypto {

    private const val MAGIC = "SKE1"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    const val DEFAULT_ITERATIONS = 120_000

    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(passphrase, salt, iterations),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return MAGIC.toByteArray(Charsets.UTF_8) + salt + iv + cipher.doFinal(plaintext)
    }

    /**
     * @throws javax.crypto.AEADBadTagException wrong passphrase or tampered blob
     * @throws IllegalArgumentException not a SnapKeys sync blob
     */
    fun decrypt(
        blob: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        val headerLength = MAGIC.length + SALT_BYTES + IV_BYTES
        require(blob.size > headerLength) { "Blob too short" }
        require(String(blob, 0, MAGIC.length, Charsets.UTF_8) == MAGIC) {
            "Not a SnapKeys sync blob"
        }
        val salt = blob.copyOfRange(MAGIC.length, MAGIC.length + SALT_BYTES)
        val iv = blob.copyOfRange(MAGIC.length + SALT_BYTES, headerLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(passphrase, salt, iterations),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(blob.copyOfRange(headerLength, blob.size))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(key.encoded, "AES")
    }
}
