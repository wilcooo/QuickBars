package dev.trooped.tvquickbars.services

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.persistence.QuickBarManager

/** On-demand overlay host. No global key listener, boot start, or sticky restart. */
class QuickBarOverlayService : Service() {
    internal var controller: QuickBarController? = null
        private set
    private var startupFailed = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.external_quickbar_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            val close = PendingIntent.getService(
                this,
                0,
                Intent(this, QuickBarOverlayService::class.java).setAction(ACTION_CLOSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_quickbar_notification)
                .setContentTitle(getString(R.string.external_quickbar_active))
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            this, R.drawable.ic_quickbar_notification
                        ),
                        getString(R.string.common_close),
                        close
                    ).build()
                )
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            startupFailed = true
            Log.e(TAG, "Could not start the overlay service", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = intent?.let {
            IntentCompat.getParcelableExtra(
                it, QuickBarRequest.EXTRA_RESULT, ResultReceiver::class.java
            )
        }
        try {
            if (startupFailed || intent?.action != QuickBarRequest.ACTION_OPEN ||
                !Settings.canDrawOverlays(this)
            ) {
                result?.send(Activity.RESULT_CANCELED, null)
                finishSession()
                return START_NOT_STICKY
            }
            val bar = QuickBarRequest.resolve(
                QuickBarManager(this).loadQuickBars(),
                intent.getStringExtra(QuickBarRequest.EXTRA_ID),
                intent.getStringExtra(QuickBarRequest.EXTRA_NAME)
            )
            if (bar == null) {
                result?.send(Activity.RESULT_CANCELED, null)
                if (controller == null) finishSession()
                return START_NOT_STICKY
            }
            if (QuickBarService.isRunning) {
                QuickBarService.serviceInstance?.openQuickBar(bar)
                result?.send(Activity.RESULT_OK, null)
                finishSession()
                return START_NOT_STICKY
            }
            val host = controller ?: QuickBarController(this, ::finishSession).also {
                controller = it
                it.initialize()
            }
            host.openQuickBar(bar)
            result?.send(
                if (controller != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, null
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not open QuickBar", e)
            result?.send(Activity.RESULT_CANCELED, null)
            finishSession()
        }
        return START_NOT_STICKY
    }

    internal fun finishSession() {
        val host = controller
        controller = null
        try {
            host?.destroy()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not clean up the overlay", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finishSession()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        internal var instance: QuickBarOverlayService? = null
        private const val TAG = "QuickBarOverlayService"
        private const val CHANNEL = "quickbar_overlay"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_CLOSE = "dev.trooped.tvquickbars.CLOSE_EXTERNAL_QUICKBAR"
    }
}
