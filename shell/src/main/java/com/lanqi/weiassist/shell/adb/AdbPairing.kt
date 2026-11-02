package com.lanqi.weiassist.shell.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pair with adbd's Wireless debugging ("Pair device with pairing code"), as `adb pair` does
 * (packages/modules/adb pairing_connection.cpp):
 * 1. TLS 1.3 to the pairing port; 2. SPAKE2 with password = code + 64 bytes exported from TLS;
 * 3. exchange PeerInfo (our adb public key) encrypted with AES-128-GCM keyed by HKDF(SPAKE2 key).
 * Afterwards adbd trusts [key] for TLS connections to its connect port.
 */
class AdbPairing(private val key: AdbKey, private val name: String) {

    class WrongCodeException : IOException("The pairing code is wrong, or the pairing dialog was closed.")

    /** Blocking: run it off the main thread. */
    fun pair(host: String, port: Int, code: String) {
        Socket().use { plain ->
            plain.connect(InetSocketAddress(host, port), 5_000)
            plain.soTimeout = 15_000
            val tls = AdbTls.wrap(plain, key)
            val input = DataInputStream(tls.inputStream)
            val output = DataOutputStream(tls.outputStream)
            val password = code.trim().toByteArray() + AdbTls.exportKeyingMaterial(tls, EXPORTED_KEY_SIZE)

            Spake2(password).use { spake ->
                writePacket(output, SPAKE2_MSG, spake.message)
                val theirMsg = readPacket(input, SPAKE2_MSG)
                val secret = spake.process(theirMsg) ?: throw WrongCodeException()
                val cipher = PairingCipher(secret)

                val info = ByteArray(PEER_INFO_SIZE)
                info[0] = ADB_RSA_PUB_KEY
                val pub = key.adbPublicKey(name).toByteArray()
                System.arraycopy(pub, 0, info, 1, minOf(pub.size, PEER_INFO_SIZE - 2)) // NUL-terminated by the zeros
                writePacket(output, PEER_INFO, cipher.encrypt(info))
                // A wrong code shows here: adbd's PeerInfo can't be decrypted.
                val theirs = try { cipher.decrypt(readPacket(input, PEER_INFO)) } catch (e: java.security.GeneralSecurityException) { throw WrongCodeException() }
                if (theirs.size != PEER_INFO_SIZE) throw IOException("unexpected PeerInfo size ${theirs.size}")
            }
        }
    }

    private fun writePacket(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeByte(VERSION)
        out.writeByte(type)
        out.writeInt(payload.size) // big-endian, as htonl
        out.write(payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream, type: Int): ByteArray {
        val version = input.readUnsignedByte()
        val t = input.readUnsignedByte()
        val size = input.readInt()
        if (version != VERSION) throw IOException("pairing version $version")
        if (t != type) throw if (type == SPAKE2_MSG) IOException("unexpected packet $t") else WrongCodeException()
        if (size <= 0 || size > MAX_PAYLOAD) throw IOException("bad payload size $size")
        return ByteArray(size).also { input.readFully(it) }
    }

    /** AES-128-GCM keyed by HKDF-SHA256, with a 64-bit little-endian counter as nonce per direction (aes_128_gcm.cpp). */
    internal class PairingCipher(secret: ByteArray) {
        private val key = SecretKeySpec(hkdfSha256(secret, "adb pairing_auth aes-128-gcm key".toByteArray(), 16), "AES")
        private var encSeq = 0L
        private var decSeq = 0L

        fun encrypt(data: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, encSeq++, data)
        fun decrypt(data: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, decSeq++, data)

        private fun run(mode: Int, seq: Long, data: ByteArray): ByteArray {
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array()
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, nonce)) }.doFinal(data)
        }
    }

    companion object {
        private const val VERSION = 1
        private const val SPAKE2_MSG = 0
        private const val PEER_INFO = 1
        private const val ADB_RSA_PUB_KEY: Byte = 0
        private const val PEER_INFO_SIZE = 8192
        private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        private const val EXPORTED_KEY_SIZE = 64

        /** RFC 5869 HKDF with SHA-256 and no salt (BoringSSL's HKDF with salt = nullptr). */
        fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = java.io.ByteArrayOutputStream()
            var t = ByteArray(0)
            var i = 1
            while (out.size() < length) {
                mac.update(t); mac.update(info); mac.update(i.toByte())
                t = mac.doFinal()
                out.write(t)
                i++
            }
            return out.toByteArray().copyOf(length)
        }
    }
}
