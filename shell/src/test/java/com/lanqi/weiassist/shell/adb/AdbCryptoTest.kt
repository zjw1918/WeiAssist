package com.lanqi.weiassist.shell.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AdbCryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `HKDF matches RFC 5869 test case 3 (no salt, as adb uses it)`() {
        val okm = AdbPairing.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), okm)
    }

    @Test fun `the pairing cipher's two directions use their own counters`() {
        val a = AdbPairing.PairingCipher(ByteArray(64) { it.toByte() })
        val b = AdbPairing.PairingCipher(ByteArray(64) { it.toByte() })
        val m1 = a.encrypt("one".toByteArray())
        val m2 = a.encrypt("two".toByteArray())
        assertEquals("one", String(b.decrypt(m1)))
        assertEquals("two", String(b.decrypt(m2)))
        assertEquals(3 + 16, m1.size) // GCM tag appended, no AAD
    }

    @Test fun `the adb public key has adb's layout and arithmetic`() {
        val key = AdbKey.generate()
        val pub = key.keyPair.public as RSAPublicKey
        val enc = AdbKey.encodeAdbPublicKey(pub)
        assertEquals(524, enc.size)
        val b = ByteBuffer.wrap(enc).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int.toLong() and 0xffffffffL
        val modulus = ByteArray(256).also { b.get(it) }.reversedArray()
        val rr = ByteArray(256).also { b.get(it) }.reversedArray()
        assertEquals(65537, b.int)
        val n = BigInteger(1, modulus)
        assertEquals(pub.modulus, n)
        // n0inv = -1/n mod 2^32
        assertEquals(BigInteger.ZERO, n.multiply(BigInteger.valueOf(n0inv)).add(BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32)))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), BigInteger(1, rr))
        val text = key.adbPublicKey("weiassist@phone")
        assertTrue(text.endsWith(" weiassist@phone"))
        assertArrayEquals(enc, Base64.getDecoder().decode(text.substringBefore(' ')))
    }

    @Test fun `the certificate carries the key and survives a round trip through storage`() {
        val key = AdbKey.generate()
        assertEquals(key.keyPair.public, key.certificate.publicKey)
        key.certificate.verify(key.keyPair.public)
        val again = AdbKey.fromPrivateKey(key.privateKeyBytes())
        assertEquals(key.keyPair.public, again.keyPair.public)
        assertEquals(key.adbPublicKey("x"), again.adbPublicKey("x")) // the key adbd knows survives a restart
    }

    @Test fun `adb messages are framed as adbd expects`() {
        val out = java.io.ByteArrayOutputStream()
        AdbConnection.writeMessage(out, AdbConnection.A_OPEN, 1, 0, "shell:id\u0000".toByteArray())
        val bytes = out.toByteArray()
        assertEquals(24 + 9, bytes.size)
        val m = AdbConnection.readMessage(bytes.inputStream())
        assertEquals(AdbConnection.A_OPEN, m.command)
        assertEquals("shell:id\u0000", String(m.data))
        assertEquals(0x4e45504f.inv(), ByteBuffer.wrap(bytes, 20, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }
}
