package com.lanqi.weiassist.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Home of the agent. Owns the one [AgentController] (and so the Pi runtime) for the whole app.
 *
 * - Screens (and later the floating assistant) bind to it and observe [AgentController.state].
 * - While Pi is working (a task, or a sign-in in the browser) it is a *started foreground* service
 *   with a notification and a Stop button, so the work survives the user leaving or closing the app.
 * - When Pi is idle it drops back to bound-only: no notification, and it ends with the last client.
 */
class AgentService : Service() {
    inner class LocalBinder : Binder() {
        val controller: AgentController get() = this@AgentService.controller
    }

    private lateinit var controller: AgentController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    /** Whether any screen is bound. With none (or the app not visible), a finished task is reported in a notification. */
    private var clients = 0
    private var lastWork: Work? = null

    override fun onCreate() {
        super.onCreate()
        controller = AgentController(this)
        createChannel()
        scope.launch {
            controller.state
                .map { s -> Work(s.run.busy, s.signingInWithBrowser, s.run.task) }
                .distinctUntilChanged()
                .collect(::onWork)
        }
    }

    private data class Work(val running: Boolean, val signingIn: Boolean, val task: String?) {
        val active get() = running || signingIn
    }

    private fun onWork(w: Work) {
        val appVisible = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (lastWork?.running == true && !w.running && (clients == 0 || !appVisible)) notifyFinished()
        lastWork = w
        if (w.active) {
            // Become a started service first, so the work outlives unbinding; then go foreground.
            if (!foreground) ContextCompat.startForegroundService(this, Intent(this, AgentService::class.java))
            goForeground(w)
        } else if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf() // still alive while screens are bound
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> controller.stop()
            else -> {
                val s = controller.state.value
                val w = Work(s.run.busy, s.signingInWithBrowser, s.run.task)
                // startForegroundService() promises startForeground(); keep it even if the work just ended.
                goForeground(w)
                if (!w.active) onWork(w)
            }
        }
        return START_NOT_STICKY // a task can't resume after the process dies; don't restart empty
    }

    private fun goForeground(w: Work) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(w), type)
            foreground = true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.w(TAG, "could not go foreground: ${e.javaClass.simpleName}")
        }
    }

    /** The app's launcher screen (this module doesn't know the UI's activity class). */
    private fun openApp(): Intent = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent())
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun notification(w: Work): Notification {
        val open = PendingIntent.getActivity(
            this, 0, openApp(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.agent_name))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (w.running) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            b.setContentText(getString(R.string.notif_running, w.task.orEmpty().take(60)))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), stop)
        } else {
            b.setContentText(getString(R.string.notif_signin))
        }
        return b.build()
    }

    /** The task ended while no screen was open: tell the user what came of it. */
    private fun notifyFinished() {
        val run = controller.state.value.run
        val open = PendingIntent.getActivity(
            this, 2, openApp(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = run.error?.let { getString(R.string.notif_failed, it) } ?: plain(run.answer).ifBlank { getString(R.string.notif_ended) }
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(if (run.error == null) R.string.notif_done_title else R.string.notif_error_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed && nm.areNotificationsEnabled()) nm.notify(NOTIFICATION_DONE, n)
        else Log.i(TAG, "result not shown: notifications are off for this app")
    }

    /** Notifications show plain text: drop the model's markdown emphasis and code marks. */
    private fun plain(md: String) = md.replace(Regex("(\\*\\*|__|`)"), "").replace(Regex("(?m)^#+\\s*"), "").trim()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_working), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_working_desc)
            },
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_results_desc)
            },
        )
    }

    override fun onBind(intent: Intent): IBinder { clients++; return LocalBinder() }
    override fun onRebind(intent: Intent?) { clients++ }
    override fun onUnbind(intent: Intent?): Boolean { clients--; return true }

    override fun onDestroy() {
        scope.cancel()
        controller.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL = "agent"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_DONE = "agent-results"
        private const val NOTIFICATION_DONE = 2
        private const val ACTION_STOP = "com.lanqi.weiassist.STOP"

        /** Bind to the agent; [onController] gets it once connected (and null if the service dies). */
        fun bind(context: Context, onController: (AgentController?) -> Unit): ServiceConnection {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) =
                    onController((service as LocalBinder).controller)
                override fun onServiceDisconnected(name: ComponentName?) = onController(null)
            }
            context.bindService(Intent(context, AgentService::class.java), connection, Context.BIND_AUTO_CREATE)
            return connection
        }
    }
}
