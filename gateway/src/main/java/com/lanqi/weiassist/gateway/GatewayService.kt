package com.lanqi.weiassist.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lanqi.weiassist.magpie.MagpieHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the gateway serving: a foreground service for as long as it is on, so Android keeps this app's process (and
 * with it magpie, its child) while the screen is off or the app is closed. It runs magpie through [MagpieHost] and
 * stops itself when the gateway is turned off.
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val host by lazy { MagpieHost.get(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    private var watching = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /** Follow the gateway once it has been started: its state on the notification, and stop when it is off. */
    private fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            host.state.collect { s ->
                if (s == MagpieHost.State.Off || s == MagpieHost.State.Unavailable) stopSelf()
                else notify(s)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() promises startForeground(), even when it is about to stop.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(host.state.value), type)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.w(TAG, "could not go foreground: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!host.enabled) { stopSelf(); return START_NOT_STICKY }
        host.autoStart()
        watch()
        return START_STICKY // a server: Android starts it again if it had to stop it
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(s: MagpieHost.State) {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, notification(s)) }
    }

    private fun notification(s: MagpieHost.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, GatewayActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (s) {
            MagpieHost.State.Running -> MagpieHost.lanAddress()
                ?.let { getString(R.string.notif_serving_lan, "http://$it:${MagpieHost.GATEWAY_PORT}") }
                ?: getString(R.string.notif_serving)
            is MagpieHost.State.Failed -> getString(R.string.status_failed, s.message)
            else -> getString(R.string.status_starting)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL = "gateway"
        private const val NOTIFICATION_ID = 1

        /** Turn the gateway on and keep it serving. */
        fun start(context: Context) {
            MagpieHost.get(context).setEnabled(true)
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }

        /** Turn it off: magpie stops, and this service with it. */
        fun stop(context: Context) {
            MagpieHost.get(context).setEnabled(false)
        }

        /** On again, if the user left it on (after a restart, an update, or the app's process ending). */
        fun resume(context: Context) {
            if (MagpieHost.get(context).enabled) {
                runCatching { ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java)) }
                    .onFailure { Log.w(TAG, "could not start: $it") }
            }
        }
    }
}
