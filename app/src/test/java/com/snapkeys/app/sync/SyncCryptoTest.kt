package com.snapkeys.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class SyncCryptoTest {

    // Low iteration count keeps the tests fast; the KDF is the same.
    private val iterations = 1_000
    private val passphrase = "correct horse battery staple".toCharArray()

    @Test
    fun `round trip returns original plaintext`() {
        val plain = """[{"trigger":"brb","expansion":"be right back"}]""".toByteArray()
        val blob = SyncCrypto.encrypt(plain, passphrase, iterations)
        assertArrayEquals(plain, SyncCrypto.decrypt(blob, passphrase, iterations))
    }

    @Test
    fun `ciphertext differs from plaintext and between runs`() {
        val plain = "hello".toByteArray()
        val first = SyncCrypto.encrypt(plain, passphrase, iterations)
        val second = SyncCrypto.encrypt(plain, passphrase, iterations)
        // Fresh random salt + IV every time — identical input must not
        // produce identical blobs, and plaintext must not leak verbatim.
        assert(!first.contentEquals(second))
        assert(String(first, Charsets.ISO_8859_1).indexOf("hello") == -1)
    }

    @Test
    fun `wrong passphrase fails authentication`() {
        val blob = SyncCrypto.encrypt("secret".toByteArray(), passphrase, iterations)
        assertThrows(AEADBadTagException::class.java) {
            SyncCrypto.decrypt(blob, "wrong passphrase".toCharArray(), iterations)
        }
    }

    @Test
    fun `tampered blob fails authentication`() {
        val blob = SyncCrypto.encrypt("secret".toByteArray(), passphrase, iterations)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) {
            SyncCrypto.decrypt(blob, passphrase, iterations)
        }
    }

    @Test
    fun `foreign blob is rejected before decryption`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncCrypto.decrypt("not a snapkeys blob at all".toByteArray(), passphrase, iterations)
        }
    }
}
