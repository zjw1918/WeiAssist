package com.lanqi.weiassist.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (a core's credentials, a bot token) at rest: one file in app-private storage,
 * AES-GCM encrypted with a key that lives in the Android Keystore and never leaves it.
 */
class SecureStore(context: Context, name: String) {
    private val file = File(context.noBackupFilesDir, "$name.bin")
    private val alias = "weiassist.$name"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun read(): String? = runCatching {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, bytes[0].toInt()))
        String(cipher.doFinal(bytes, 1 + bytes[0], bytes.size - 1 - bytes[0]), Charsets.UTF_8)
    }.getOrNull()

    fun write(text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
        tmp.renameTo(file)
    }

    /** Forget what is stored. */
    fun clear() { file.delete() }
}
