package com.lanqi.weiassist.shell.adb

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
 * The app's adb client key at rest: its PKCS#8 private key, AES-GCM encrypted with a key that lives in
 * the Android Keystore. (The RSA key itself can't live in the Keystore: Conscrypt must sign with it.)
 * Not backed up: a key restored on another phone would be useless, and it grants shell access here.
 */
class AdbKeyStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "adb-key.bin")

    /** The stored key, or a new one (stored at once). */
    @Synchronized fun getOrCreate(): AdbKey {
        val stored = read()
        if (stored != null) {
            runCatching { AdbKey.fromPrivateKey(stored) }
                .onSuccess { return it }
                .onFailure { android.util.Log.e(TAG, "stored adb key unusable; making a new one", it) }
        } else if (file.exists()) android.util.Log.e(TAG, "stored adb key unreadable; making a new one")
        return create()
    }

    fun exists() = file.exists()

    /** Forget the key: adbd will then need a new pairing. */
    @Synchronized fun delete() { file.delete() }

    private fun create() = AdbKey.generate().also { write(it.privateKeyBytes()) }

    private fun aes(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun read(): ByteArray? = runCatching<ByteArray?> {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aes(), GCMParameterSpec(128, bytes, 1, bytes[0].toInt()))
        cipher.doFinal(bytes, 1 + bytes[0], bytes.size - 1 - bytes[0])
    }.onFailure { android.util.Log.e(TAG, "reading the adb key failed", it) }.getOrNull()

    private fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aes())
        val iv = cipher.iv
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain))
        tmp.renameTo(file)
    }

    private companion object {
        const val ALIAS = "weiassist.adb-key"
        const val TAG = "AdbKeyStore"
    }
}
