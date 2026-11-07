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
import kotlinx.coroutines.launch

/**
 * Home of the agent. Owns the one [AgentController] (and so the Pi runtime and every session) for the whole app.
 *
 * - Screens (and later the floating assistant) bind to it and observe [AgentController.state].
 * - While any session is working (or a sign-in waits in the browser) it is a *started foreground*
 *   service with a notification and a Stop button, so the work survives the user leaving or closing the app.
 * - While the Telegram bot is on ([Gateway]) it stays started too, so messages from the chat arrive any time.
 * - Otherwise it drops back to bound-only: no notification, and it ends with the last client.
 */
class AgentService : Service() {
    inner class LocalBinder : Binder() {
        val controller: AgentController get() = this@AgentService.controller
    }

    private lateinit var controller: AgentController
    private val magpie by lazy { com.lanqi.weiassist.magpie.MagpieHost.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    /** Whether any screen is bound. With none (or the app not visible), a finished task is reported in a notification. */
    private var clients = 0
    private var lastWork: Work? = null

    override fun onCreate() {
        super.onCreate()
        controller = AgentController(this)
        createChannel()
        // The shell helper (for virtual displays): start it if it was set up and isn't running (e.g. after a reboot).
        com.lanqi.weiassist.shell.ShellBackend.get(this).autoStart()
        // The AI gateway, if the user left it on.
        magpie.autoStart()
        scope.launch {
            kotlinx.coroutines.flow.combine(controller.state, controller.sessions.summaries, controller.gateway.state, magpie.state, ::work)
                .distinctUntilChanged()
                .collect(::onWork)
        }
        scope.launch { controller.sessions.asked.collect(::onAsked) }
        scope.launch {
            controller.sessions.finished.collect { f ->
                launch {
                    // It drove the phone's own screen: the last app it used is in front. Come back to its answer.
                    if (f.onMainScreen && !appVisible() && returnToApp(f.id)) {
                        kotlinx.coroutines.delay(RETURN_CHECK_MS)
                        if (appVisible()) return@launch
                        Log.i(TAG, "could not come back to the front (blocked?): notifying instead")
                    }
                    if (clients == 0 || !appVisible()) notifyFinished(f.id)
                }
            }
        }
    }

    /**
     * A session's agent waits for the user's answer: unless its chat is open in front of them, a notification says
     * so (it goes when the question is answered or withdrawn).
     */
    private fun onAsked(a: SessionManager.Asked) {
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (!a.open) { nm.cancel(a.id, NOTIFICATION_ASK); return }
        if (appVisible() && clients > 0 && controller.sessions.viewingId == a.id) return
        val s = controller.sessions.summary(a.id) ?: return
        val open = PendingIntent.getActivity(this, a.id.hashCode(), openApp(a.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = a.ask.questions.joinToString("\n") { it.text }
        val n = NotificationCompat.Builder(this, CHANNEL_ASK)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle(getString(R.string.notif_ask_title, s.title.ifBlank { getString(R.string.agent_name) }))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed && nm.areNotificationsEnabled()) nm.notify(a.id, NOTIFICATION_ASK, n)
    }

    private fun work(s: AgentState, list: List<SessionSummary>, g: Gateway.State, m: com.lanqi.weiassist.magpie.MagpieHost.State): Work {
        val active = list.filter { it.status.active }
        val serving = m != com.lanqi.weiassist.magpie.MagpieHost.State.Off && m != com.lanqi.weiassist.magpie.MagpieHost.State.Unavailable
        return Work(active.size, s.signingInWithBrowser, active.singleOrNull()?.title, g.bot.takeIf { g.listening }, serving)
    }

    /**
     * [running] = sessions with work (running, queued or paused); [task] names it when there is one.
     * [listening]: the Telegram bot (its @username) that messages may come to at any time.
     * [serving]: the AI gateway is on, for this phone's apps and the devices on its network.
     */
    private data class Work(
        val running: Int, val signingIn: Boolean, val task: String?, val listening: String? = null, val serving: Boolean = false,
    ) {
        val active get() = running > 0 || signingIn || listening != null || serving
    }

    private fun onWork(w: Work) {
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
            ACTION_STOP -> controller.stopAll()
            else -> {
                val w = work(controller.state.value, controller.sessions.summaries.value, controller.gateway.state.value, magpie.state.value)
                // startForegroundService() promises startForeground(); keep it even if the work just ended.
                goForeground(w)
                if (!w.active) onWork(w)
            }
        }
        return START_NOT_STICKY // a task can't resume by itself after the process dies (it shows as Interrupted)
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

    private fun appVisible() = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    /**
     * Brings the app to the front at the session's page. Allowed from the background because the app's
     * accessibility service is bound by the system (Android's exemption for background activity starts);
     * some phones still block it, so the caller checks and falls back to the notification.
     */
    private fun returnToApp(session: String): Boolean = runCatching {
        startActivity(openApp(session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }.onFailure { Log.w(TAG, "could not come back to the front: $it") }.isSuccess

    /** The app's launcher screen (this module doesn't know the UI's activity class), optionally at a session. */
    private fun openApp(session: String? = null): Intent = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent())
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { if (session != null) putExtra(EXTRA_SESSION, session) }

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
        if (w.running > 0) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            val text = if (w.running == 1) getString(R.string.notif_running, w.task.orEmpty().take(60))
            else resources.getQuantityString(R.plurals.notif_running_many, w.running, w.running)
            b.setContentText(text)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(if (w.running == 1) R.string.notif_stop else R.string.notif_stop_all), stop)
        } else if (w.signingIn) {
            b.setContentText(getString(R.string.notif_signin))
        } else if (w.listening != null) {
            b.setContentText(getString(R.string.notif_listening, w.listening))
        } else {
            b.setContentText(getString(R.string.notif_gateway))
        }
        return b.build()
    }

    /** A session's task ended while no screen was open: tell the user what came of it (one notification per session). */
    private fun notifyFinished(id: String) {
        val s = controller.sessions.summary(id) ?: return
        val open = PendingIntent.getActivity(
            this, id.hashCode(), openApp(id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (s.error) getString(R.string.notif_failed, s.preview) else plain(s.preview).ifBlank { getString(R.string.notif_ended) }
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(if (!s.error) R.string.notif_done_title else R.string.notif_error_title, s.title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed && nm.areNotificationsEnabled()) nm.notify(id, NOTIFICATION_DONE, n)
        else Log.i(TAG, "result not shown: notifications are off for this app")
    }

    /** Notifications show plain text: drop the model's markdown emphasis and code marks. */
    private fun plain(md: String) = Markdown.plain(md).trim()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_working), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_working_desc)
            },
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ASK, getString(R.string.channel_questions), NotificationManager.IMPORTANCE_HIGH),
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
        private const val CHANNEL_ASK = "agent-questions"
        private const val NOTIFICATION_ASK = 3
        private const val ACTION_STOP = "com.lanqi.weiassist.STOP"
        /** How long the app has to come to the front before the result is shown as a notification instead. */
        private const val RETURN_CHECK_MS = 1500L
        /** Intent extra: the session to open (from a result notification). */
        const val EXTRA_SESSION = "com.lanqi.weiassist.SESSION"

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
