package dev.trooped.tvquickbars.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.persistence.QuickBarManager

/** On-demand overlay host. No global key listener, boot start, or sticky restart. */
class QuickBarOverlayService : Service() {
    internal var controller: QuickBarController? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.external_quickbar_channel), NotificationManager.IMPORTANCE_LOW
        ))
        val close = PendingIntent.getService(this, 0,
            Intent(this, QuickBarOverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_icon)
            .setContentTitle(getString(R.string.external_quickbar_active))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null,
                getString(R.string.external_quickbar_close), close).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != QuickBarRequest.ACTION_OPEN || !Settings.canDrawOverlays(this)) {
            finishSession()
            return START_NOT_STICKY
        }
        val bar = QuickBarRequest.resolve(QuickBarManager(this).loadQuickBars(),
            intent.getStringExtra(QuickBarRequest.EXTRA_ID),
            intent.getStringExtra(QuickBarRequest.EXTRA_NAME))
        if (bar == null) {
            if (controller == null) finishSession()
            return START_NOT_STICKY
        }
        if (QuickBarService.isRunning) {
            QuickBarService.serviceInstance?.openQuickBar(bar)
            finishSession()
            return START_NOT_STICKY
        }
        val host = controller ?: QuickBarController(this, ::finishSession).also {
            controller = it
            it.initialize()
        }
        host.openQuickBar(bar)
        return START_NOT_STICKY
    }

    internal fun finishSession() {
        controller?.destroy()
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile internal var instance: QuickBarOverlayService? = null
        private const val CHANNEL = "quickbar_overlay"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_CLOSE = "dev.trooped.tvquickbars.CLOSE_EXTERNAL_QUICKBAR"
    }
}
