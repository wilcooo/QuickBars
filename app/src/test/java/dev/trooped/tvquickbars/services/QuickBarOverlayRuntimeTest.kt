package dev.trooped.tvquickbars.services

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import dev.trooped.tvquickbars.data.QuickBar
import dev.trooped.tvquickbars.persistence.QuickBarManager
import dev.trooped.tvquickbars.ui.OpenQuickBarActivity
import dev.trooped.tvquickbars.utils.QuickBarDiagnostics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QuickBarOverlayRuntimeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val bar = QuickBar(id = "runtime-test", name = "Runtime test")
    private fun idle(ms: Long = 100) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun root(controller: QuickBarController): View? =
        QuickBarController::class.java.getDeclaredField("overlayView").run {
            isAccessible = true
            get(controller) as View?
        }
    private fun back(view: View) {
        assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        idle(500)
    }

    @Before fun configure() {
        ShadowSettings.setCanDrawOverlays(true)
        // Robolectric does not automatically grant AndroidX's signature permission on API 28.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        QuickBarManager(app).saveQuickBars(listOf(bar))
    }

    @Test fun opensOverlayWithoutAccessibilityAndClosesCleanly() {
        var closed = false
        val controller = QuickBarController(app) { closed = true }
        try {
            controller.initialize()
            controller.openQuickBar(bar)
            // Regression: this traversal threw ViewTreeLifecycleOwner-not-found
            // after the launch activity had already finished in the first APK.
            idle()
            assertFalse(QuickBarService.isRunning)
            assertFalse("Overlay must stay open after attachment", closed)
            val first = requireNotNull(root(controller))
            assertTrue(first.isAttachedToWindow)
            assertNotNull(first.findViewTreeLifecycleOwner())
            assertNotNull(first.findViewTreeSavedStateRegistryOwner())
            controller.openQuickBar(bar)
            idle()
            assertSame("Repeated OPEN must not toggle the bar closed", first, root(controller))
            back(first)
            assertTrue(closed)
            assertNull(root(controller))
        } catch (error: Throwable) {
            try { controller.destroy() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
        controller.destroy()
    }

    @Test fun coldServiceLaunchAcknowledgesAndStopsAfterBack() {
        var result: Int? = null
        val request = Intent(app, QuickBarOverlayService::class.java)
            .setAction(QuickBarRequest.ACTION_OPEN).putExtra(QuickBarRequest.EXTRA_ID, bar.id)
            .putExtra(QuickBarRequest.EXTRA_RESULT, object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) { result = resultCode }
            })
        val lifecycle = Robolectric.buildService(QuickBarOverlayService::class.java).create()
        val service = lifecycle.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(request, 0, 1))
            idle()
            assertEquals(Activity.RESULT_OK, result)
            assertFalse(QuickBarService.isRunning)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            back(requireNotNull(root(requireNotNull(service.controller))))
            assertNull(service.controller)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(shadowOf(service).isForegroundStopped)
        } finally { lifecycle.destroy() }
    }

    @Test fun missingOverlayPermissionRejectsLaunchAndStopsService() {
        ShadowSettings.setCanDrawOverlays(false)
        val lifecycle = Robolectric.buildService(QuickBarOverlayService::class.java).create()
        val service = lifecycle.get()
        try {
            val intent = Intent().setAction(QuickBarRequest.ACTION_OPEN).putExtra(QuickBarRequest.EXTRA_ID, bar.id)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            assertNull(service.controller)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { lifecycle.destroy() }
    }

    @Test fun inAppShowUsesTheExistingShortcutContract() {
        val intent = OpenQuickBarActivity.createOpenIntent(app, bar.id)
        assertEquals(QuickBarRequest.ACTION_OPEN, intent.action)
        assertEquals(bar.id, intent.getStringExtra(QuickBarRequest.EXTRA_ID))
        assertEquals(OpenQuickBarActivity::class.java.name, intent.component?.className)
    }

    @Test fun diagnosticsRetainFailureAndRedactConnectionDetails() {
        QuickBarDiagnostics.failure(app, "Startup test", IllegalStateException(
            "https://private.example/api Bearer secret-value " + "x".repeat(60)))
        val report = QuickBarDiagnostics.report(app)
        assertTrue(report.contains("Startup test"))
        assertTrue(report.contains("IllegalStateException"))
        assertFalse(report.contains("private.example"))
        assertFalse(report.contains("secret-value"))
        assertFalse(report.contains("x".repeat(60)))
    }
}
