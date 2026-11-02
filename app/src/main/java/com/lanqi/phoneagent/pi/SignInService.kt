package com.lanqi.phoneagent.pi

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lanqi.phoneagent.MainActivity

/**
 * Keeps the app alive while the user signs in to ChatGPT in Chrome.
 *
 * During sign-in our app sits in the background, and Chrome must later reach Pi's callback socket on
 * 127.0.0.1:1455. Battery managers freeze background apps (realme's OplusHansManager froze ours ~25 s
 * after Chrome came to the front), so the redirect hung. A short foreground service with a visible
 * notification tells the system the app is doing something the user asked for. It does no work itself
 * and is stopped as soon as Pi reports the login finished, failed or was cancelled.
 */
class SignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "登录", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("小维助手")
            .setContentText("正在等待 ChatGPT 登录…点这里回到小维助手完成登录")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, ID, notification, type)
        return START_NOT_STICKY
    }

    // Android 14+: a shortService gets about 3 minutes; if sign-in takes longer, just stop.
    override fun onTimeout(startId: Int) = stopSelf()
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    companion object {
        private const val CHANNEL = "sign-in"
        private const val ID = 1455
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, SignInService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, SignInService::class.java))
    }
}
