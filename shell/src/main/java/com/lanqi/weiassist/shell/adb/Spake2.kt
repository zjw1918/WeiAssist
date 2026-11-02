package com.lanqi.weiassist.shell.adb

/** BoringSSL's SPAKE2 as adb's pairing uses it (the client side, "alice"); see cpp/adbpair.cpp. */
internal class Spake2(password: ByteArray) : AutoCloseable {
    private var handle = nativeNew(password).also { check(it != 0L) { "SPAKE2 setup failed" } }

    /** Our message for the server. */
    val message: ByteArray get() = nativeMsg(handle)

    /** The shared key from the server's message; null when the codes didn't match. */
    fun process(theirs: ByteArray): ByteArray? = nativeProcess(handle, theirs)

    override fun close() { if (handle != 0L) { nativeFree(handle); handle = 0L } }

    private companion object {
        init { System.loadLibrary("adbpair") }
        @JvmStatic external fun nativeNew(password: ByteArray): Long
        @JvmStatic external fun nativeMsg(handle: Long): ByteArray
        @JvmStatic external fun nativeProcess(handle: Long, theirs: ByteArray): ByteArray?
        @JvmStatic external fun nativeFree(handle: Long)
    }
}
