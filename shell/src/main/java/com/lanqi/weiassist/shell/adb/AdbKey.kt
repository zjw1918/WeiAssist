package com.lanqi.weiassist.shell.adb

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The key this app uses as an adb client. adbd remembers its public key when the user pairs, and
 * accepts TLS connections from a certificate with that key afterwards. Pure JVM (unit-tested); storing
 * it is [AdbKeyStore]'s job.
 */
class AdbKey(val keyPair: KeyPair, val certificate: X509Certificate) {
    private val publicKey get() = keyPair.public as RSAPublicKey

    /** adb's own public key format (android_pubkey_encode), base64, then a name: what adbd stores in adb_keys. */
    fun adbPublicKey(name: String): String = Base64.getEncoder().encodeToString(encodeAdbPublicKey(publicKey)) + " " + name

    /** The private key as PKCS#8, for storage. */
    fun privateKeyBytes(): ByteArray = keyPair.private.encoded

    companion object {
        private const val KEY_BITS = 2048

        fun generate(): AdbKey {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4)) }.generateKeyPair()
            return AdbKey(kp, selfSignedCertificate(kp))
        }

        /** Rebuild from a stored PKCS#8 private key (the public key and certificate follow from it). */
        fun fromPrivateKey(pkcs8: ByteArray): AdbKey {
            val kf = KeyFactory.getInstance("RSA")
            // Android's provider hands back a plain RSAPrivateKey (not RSAPrivateCrtKey) here; our keys are
            // generated with the standard public exponent, so the modulus is all we need.
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as java.security.interfaces.RSAPrivateKey
            val e = (priv as? java.security.interfaces.RSAPrivateCrtKey)?.publicExponent ?: RSAKeyGenParameterSpec.F4
            val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, e))
            val kp = KeyPair(pub, priv)
            return AdbKey(kp, selfSignedCertificate(kp))
        }

        /**
         * struct RSAPublicKey { u32 modulus_size_words; u32 n0inv; u8 modulus[256]; u8 rr[256]; u32 exponent; },
         * little-endian, as libcrypto_utils' android_pubkey_encode writes it.
         */
        fun encodeAdbPublicKey(key: RSAPublicKey): ByteArray {
            val n = key.modulus
            val words = KEY_BITS / 32
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32)
            val rr = BigInteger.ONE.shiftLeft(KEY_BITS).pow(2).mod(n)
            return ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(words)
                putInt(n0inv.toInt())
                put(littleEndian(n, 256))
                put(littleEndian(rr, 256))
                putInt(key.publicExponent.toInt())
            }.array()
        }

        private fun littleEndian(v: BigInteger, size: Int): ByteArray {
            val be = v.toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
            val out = ByteArray(size)
            for (i in be.indices) out[i] = be[be.size - 1 - i]
            return out
        }

        // ---- a minimal self-signed X.509 certificate (adbd only looks at its public key) -------------

        private val SHA256_WITH_RSA = byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
        private val OID_CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

        fun selfSignedCertificate(kp: KeyPair, cn: String = "WeiAssist"): X509Certificate {
            val algorithm = seq(SHA256_WITH_RSA, byteArrayOf(0x05, 0x00))
            val name = seq(set(seq(OID_CN, tlv(0x0c, cn.toByteArray()))))
            // A fixed, long validity: the certificate is rebuilt from the key on every start anyway.
            val validity = seq(time("20250101000000Z"), time("20991231235959Z"))
            val tbs = seq(
                tlv(0xa0, integer(BigInteger.valueOf(2))), // v3
                integer(BigInteger.ONE),
                algorithm, name, validity, name,
                kp.public.encoded, // SubjectPublicKeyInfo, already DER
            )
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(kp.private); update(tbs) }.sign()
            val der = seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + sig))
            return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        }

        private fun time(t: String): ByteArray =
            if (t.startsWith("20") && t.substring(0, 4).toInt() < 2050) tlv(0x17, t.substring(2).toByteArray()) else tlv(0x18, t.toByteArray())

        private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
        private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
        private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

        private fun tlv(tag: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = value.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
            }
            out.write(value)
            return out.toByteArray()
        }
    }
}
