package com.lanqi.weiassist.shell.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Wireless debugging announces its ports over mDNS: `_adb-tls-pairing._tcp` while the "pair with code"
 * dialog is open, `_adb-tls-connect._tcp` while Wireless debugging is on. Only services on this phone
 * count (its own addresses); every other phone on the network is ignored.
 */
class AdbMdns(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Ports of this phone's [type] service as they come and go (null = none now). */
    fun ports(type: String): Flow<Int?> = callbackFlow {
        val found = LinkedHashMap<String, Int>()
        // Android 11-13 resolve one service at a time (FAILURE_ALREADY_ACTIVE otherwise): queue them.
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        fun next() {
            if (resolving) return
            val info = pending.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION") // resolveService: the API 34 replacement isn't on Android 11-13
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, error: Int) {
                    Log.d(TAG, "resolve ${i.serviceName} failed $error")
                    synchronized(pending) { resolving = false; next() }
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    @Suppress("DEPRECATION") val host = i.host
                    Log.d(TAG, "resolved ${i.serviceName} → $host:${i.port}")
                    if (host != null && isThisPhone(host)) {
                        synchronized(pending) { found[i.serviceName] = i.port }
                        trySend(i.port)
                    }
                    synchronized(pending) { resolving = false; next() }
                }
            })
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.d(TAG, "found ${info.serviceName} ${info.serviceType}")
                synchronized(pending) {
                    if (pending.none { it.serviceName == info.serviceName }) pending.addLast(info)
                    next()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(pending) {
                    pending.removeAll { it.serviceName == info.serviceName }
                    if (found.remove(info.serviceName) != null) trySend(found.values.lastOrNull())
                }
            }
            override fun onDiscoveryStarted(serviceType: String) { Log.d(TAG, "discovering $serviceType") }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close(IllegalStateException("mDNS discovery failed: $errorCode")) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        trySend(null)
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private fun isThisPhone(host: InetAddress): Boolean = host.isLoopbackAddress || runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { ni -> ni.inetAddresses.toList().any { it == host } }
    }.getOrDefault(false)

    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
        private const val TAG = "AdbMdns"
    }
}
