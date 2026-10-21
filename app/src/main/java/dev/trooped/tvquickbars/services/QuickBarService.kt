package dev.trooped.tvquickbars.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.trooped.tvquickbars.notification.NotificationSpec

/** System-bound host for the existing remote key mappings. */
class QuickBarService : AccessibilityService() {
    private val controller by lazy { QuickBarController(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        QuickBarOverlayService.instance?.finishSession()
        controller.initialize()
        accessibilityController = controller
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Editors send reload commands even when accessibility is disabled.
        // They must not leave an unbound accessibility service running idle.
        if (!isRunning) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        controller.handleCommand(intent)
        return START_STICKY
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean = controller.onKeyEvent(event)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRunning = false
        if (accessibilityController === controller) accessibilityController = null
        controller.destroy()
        super.onDestroy()
    }

    companion object {
        @Volatile var isRunning = false
        @Volatile private var accessibilityController: QuickBarController? = null
        internal val serviceInstance: QuickBarController?
            get() = accessibilityController ?: QuickBarOverlayService.instance?.controller

        const val ACTION_SHOW_CAMERA_PIP = "dev.trooped.tvquickbars.SHOW_CAMERA_PIP"
        const val EXTRA_CAMERA_SPEC = "CAMERA_SPEC"

        fun handleNotificationFromHa(spec: NotificationSpec) {
            serviceInstance?.handleNotificationFromHa(spec)
        }
    }
}
