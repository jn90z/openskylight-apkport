package com.jn90z.openskylight

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.generators.SCrypt
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object NativeSecurity {
    private const val ALIAS = "openskylight.secrets.v1"
    private val random = SecureRandom()

    fun random(size: Int): String {
        require(size in 1..1024)
        return b64(ByteArray(size).also(random::nextBytes))
    }

    fun sha256(value: String): String = b64(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    fun scrypt(value: String, salt: String, size: Int): String {
        require(size == 32)
        return b64(SCrypt.generate(value.toByteArray(UTF_8), unb64(salt), 16384, 8, 1, size))
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "The saved encryption key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        return b64(cipher.iv + cipher.doFinal(value.toByteArray(UTF_8)))
    }

    fun decrypt(value: String): String {
        val blob = unb64(value)
        require(blob.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, blob, 0, 12))
        return String(cipher.doFinal(blob, 12, blob.size - 12), UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(value: String) = Base64.decode(value, Base64.DEFAULT)
}
