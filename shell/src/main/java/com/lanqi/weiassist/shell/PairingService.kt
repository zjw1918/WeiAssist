package com.lanqi.weiassist.shell

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
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lanqi.weiassist.shell.adb.AdbPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pairing with Wireless debugging, on this same phone. The pairing code is shown in a Settings dialog
 * that closes (and stops listening) when the user switches apps, so the code is typed into this
 * service's notification instead (an inline reply), while the dialog stays open.
 *
 * A foreground service while it runs: the app may be frozen in the background otherwise (some OEMs).
 */
class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var backend: ShellBackend
    /** The open pairing dialog's port, or null while it isn't open. */
    private var port: Int? = null
    private var message: String? = null
    private var busy = false
    private var watch: Job? = null

    override fun onCreate() {
        super.onCreate()
        backend = ShellBackend.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.shell_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.shell_channel_desc)
                setSound(null, null)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        show()
        when (intent?.action) {
            ACTION_CODE -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.let(::pair)
            ACTION_CANCEL -> { stop(); return START_NOT_STICKY }
            else -> if (watch == null) watch = scope.launch {
                backend.pairingPorts().collect { p ->
                    Log.d(TAG, "pairing port: $p")
                    port = p
                    if (!busy) { message = null; show() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun pair(code: String) {
        val p = port
        if (p == null) { message = getString(R.string.shell_pair_no_dialog); show(); return }
        busy = true
        message = getString(R.string.shell_pairing)
        show()
        scope.launch {
            try {
                backend.pair(p, code.filter(Char::isDigit))
                message = getString(R.string.shell_paired_starting)
                show()
                val ok = backend.start() != null
                message = getString(if (ok) R.string.shell_ready else R.string.shell_paired_not_started)
                show(done = true)
                delay(4_000)
                stop()
            } catch (e: AdbPairing.WrongCodeException) {
                message = getString(R.string.shell_wrong_code)
                busy = false
                show()
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                message = getString(R.string.shell_pair_failed, e.message ?: e.javaClass.simpleName)
                busy = false
                show()
            }
        }
    }

    private fun show(done: Boolean = false) {
        val n = notification(done)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }
            .onFailure { Log.w(TAG, "could not go foreground: $it") }
    }

    private fun notification(done: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.shell_pair_title))
            .setOnlyAlertOnce(port == null || busy)
            .setOngoing(!done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        val text = message ?: getString(if (port == null) R.string.shell_pair_open_dialog else R.string.shell_pair_enter_code)
        b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        if (done) return b.build()
        if (port != null && !busy) {
            val reply = PendingIntent.getService(
                this, 1, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // mutable: RemoteInput fills it in
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.shell_code_label)).build()
            b.addAction(NotificationCompat.Action.Builder(0, getString(R.string.shell_enter_code), reply).addRemoteInput(input).build())
        } else if (port == null) {
            b.addAction(0, getString(R.string.shell_open_settings), PendingIntent.getActivity(this, 2, wirelessDebuggingSettings(), PendingIntent.FLAG_IMMUTABLE))
        }
        b.addAction(0, getString(R.string.shell_cancel), PendingIntent.getService(this, 3, Intent(this, PairingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE))
        return b.build()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PairingService"
        private const val CHANNEL = "shell-pairing"
        private const val NOTIFICATION_ID = 40
        private const val ACTION_CODE = "com.lanqi.weiassist.shell.CODE"
        private const val ACTION_CANCEL = "com.lanqi.weiassist.shell.CANCEL"
        private const val KEY_CODE = "code"

        /** Start pairing: the notification guides the user, and Settings opens at Developer options. */
        fun begin(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PairingService::class.java))
            runCatching { context.startActivity(wirelessDebuggingSettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        /** Developer options, scrolled to Wireless debugging where Settings supports it. */
        fun wirelessDebuggingSettings(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
