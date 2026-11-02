package com.lanqi.weiassist.shell.adb

import org.conscrypt.Conscrypt
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.3 as adbd speaks it, through a bundled Conscrypt (the platform's can't export keying material
 * through public API). We present our [AdbKey]'s certificate; adbd's own certificate is self-signed and
 * the connection is to this phone's loopback, so any server certificate is accepted.
 */
internal object AdbTls {
    private val provider by lazy { Conscrypt.newProvider() }

    fun wrap(plain: Socket, key: AdbKey): SSLSocket {
        val ctx = SSLContext.getInstance("TLSv1.3", provider)
        ctx.init(arrayOf(KeyManager(key)), arrayOf(TrustAll), null)
        val tls = ctx.socketFactory.createSocket(plain, plain.inetAddress.hostAddress, plain.port, true) as SSLSocket
        tls.useClientMode = true
        tls.startHandshake()
        return tls
    }

    /** RFC 5705 keying material ("adb-label" with its NUL, no context), as pairing needs. */
    fun exportKeyingMaterial(tls: SSLSocket, length: Int): ByteArray =
        Conscrypt.exportKeyingMaterial(tls, "adb-label\u0000", null, length) ?: error("TLS export failed")

    private class KeyManager(private val key: AdbKey) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(key.certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = key.keyPair.private
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    @android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val ALIAS = "adb"
}
