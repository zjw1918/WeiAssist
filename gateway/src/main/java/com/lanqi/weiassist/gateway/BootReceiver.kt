package com.lanqi.weiassist.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the phone starts, or this app is updated: serve again if the user left the gateway on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> GatewayService.resume(context)
        }
    }
}
