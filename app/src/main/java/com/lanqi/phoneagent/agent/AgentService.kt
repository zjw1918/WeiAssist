package com.lanqi.phoneagent.agent

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
import com.lanqi.phoneagent.MainActivity
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

    private fun notification(w: Work): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("小维助手")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (w.running) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            b.setContentText("正在执行：${w.task.orEmpty().take(60)}")
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
        } else {
            b.setContentText("正在等待 ChatGPT 登录…点这里回到小维助手完成登录")
        }
        return b.build()
    }

    /** The task ended while no screen was open: tell the user what came of it. */
    private fun notifyFinished() {
        val run = controller.state.value.run
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = run.error?.let { "没有完成：$it" } ?: plain(run.answer).ifBlank { "任务结束了。" }
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(if (run.error == null) "小维助手：完成了" else "小维助手：出了点问题")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_DONE, n) }
            .onFailure { Log.w(TAG, "cannot post result (notifications off?)") }
    }

    /** Notifications show plain text: drop the model's markdown emphasis and code marks. */
    private fun plain(md: String) = md.replace(Regex("(\\*\\*|__|`)"), "").replace(Regex("(?m)^#+\\s*"), "").trim()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "小维助手工作中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "小维助手执行任务或等待登录时显示，可以随时停止。"
            },
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "任务结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "你离开小维助手时，任务完成后告诉你结果。"
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
        private const val ACTION_STOP = "com.lanqi.phoneagent.STOP"

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
