package dev.trooped.tvquickbars.services

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import dev.trooped.tvquickbars.data.QuickBar
import dev.trooped.tvquickbars.data.TriggerKey
import dev.trooped.tvquickbars.persistence.QuickBarManager
import dev.trooped.tvquickbars.persistence.TriggerKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QuickBarOverlayRuntimeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val bar = QuickBar(id = "runtime-test", name = "Runtime test")

    private fun idle(ms: Long = 100) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun root(controller: QuickBarController): View? =
        QuickBarController::class.java.getDeclaredField("overlayView").run {
            isAccessible = true
            get(controller) as View?
        }

    private fun currentBarId(controller: QuickBarController): String? =
        QuickBarController::class.java.getDeclaredField("currentBarId").run {
            isAccessible = true
            get(controller) as String?
        }

    private fun back(view: View) {
        assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        idle(500)
    }

    private fun openIntent(id: String = bar.id) =
        Intent(app, QuickBarOverlayService::class.java)
            .setAction(QuickBarRequest.ACTION_OPEN)
            .putExtra(QuickBarRequest.EXTRA_ID, id)

    @Before
    fun configure() {
        ShadowSettings.setCanDrawOverlays(true)
        // Robolectric does not automatically grant AndroidX's signature permission on API 28.
        shadowOf(app).grantPermissions(
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        QuickBarManager(app).saveQuickBars(listOf(bar))
    }

    @Test
    fun opensOverlayWithoutAccessibilityAndClosesCleanly() {
        var closed = false
        val controller = QuickBarController(app) { closed = true }
        try {
            controller.initialize()
            controller.openQuickBar(bar)
            // Regression: the first APK crashed during traversal because the
            // outer Back-key wrapper did not have Compose's lifecycle owners.
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
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun latestRequestWinsWhileSwitchingBars() {
        val second = bar.copy(id = "second", name = "Second")
        val third = bar.copy(id = "third", name = "Third")
        QuickBarManager(app).saveQuickBars(listOf(bar, second, third))
        var closed = false
        val controller = QuickBarController(app) { closed = true }
        try {
            controller.initialize()
            controller.openQuickBar(bar)
            idle()
            controller.openQuickBar(second)
            controller.openQuickBar(third)
            idle(500)
            assertEquals(third.id, currentBarId(controller))
            assertTrue(requireNotNull(root(controller)).isAttachedToWindow)
            assertFalse("Switching bars must not end the session", closed)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun localNavigationRestartsAutoHideAndTimeoutStopsService() {
        bar.autoCloseDelay = 1
        QuickBarManager(app).saveQuickBars(listOf(bar))
        val lifecycle = Robolectric.buildService(QuickBarOverlayService::class.java).create()
        val service = lifecycle.get()
        try {
            service.onStartCommand(openIntent(), 0, 1)
            idle(700)
            val view = requireNotNull(root(requireNotNull(service.controller)))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
            idle(700)
            assertTrue(view.isAttachedToWindow)
            idle(800)
            assertNull(service.controller)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun coldServiceLaunchAcknowledgesAndStopsAfterBack() {
        var result: Int? = null
        val request = openIntent().putExtra(
            QuickBarRequest.EXTRA_RESULT,
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    result = resultCode
                }
            }
        )
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
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun missingOverlayPermissionRejectsLaunchAndStopsService() {
        ShadowSettings.setCanDrawOverlays(false)
        val lifecycle = Robolectric.buildService(QuickBarOverlayService::class.java).create()
        val service = lifecycle.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(openIntent(), 0, 1))
            assertNull(service.controller)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun invalidRequestDoesNotCloseAnExistingBar() {
        val lifecycle = Robolectric.buildService(QuickBarOverlayService::class.java).create()
        val service = lifecycle.get()
        try {
            service.onStartCommand(openIntent(), 0, 1)
            idle()
            val view = root(requireNotNull(service.controller))
            service.onStartCommand(openIntent("missing"), 0, 2)
            idle()
            assertSame(view, root(requireNotNull(service.controller)))
            assertFalse(shadowOf(service).isStoppedBySelf)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun accessibilityHostStillHandlesSingleDoubleAndLongPresses() {
        val second = bar.copy(id = "double", name = "Double")
        val third = bar.copy(id = "long", name = "Long")
        QuickBarManager(app).saveQuickBars(listOf(bar, second, third))
        TriggerKeyManager(app).saveTriggerKeys(
            listOf(
                TriggerKey(
                    keyCode = KeyEvent.KEYCODE_F1,
                    keyName = "F1",
                    singlePressAction = bar.id,
                    singlePressActionType = "quickbar",
                    doublePressAction = second.id,
                    doublePressActionType = "quickbar",
                    longPressAction = third.id,
                    longPressActionType = "quickbar"
                )
            )
        )
        val lifecycle = Robolectric.buildService(QuickBarService::class.java).create()
        val service = lifecycle.get()
        try {
            // Android invokes these protected accessibility callbacks, rather
            // than starting the service through onStartCommand alone.
            QuickBarService::class.java.getDeclaredMethod("onServiceConnected").apply {
                isAccessible = true
            }.invoke(service)
            service.onStartCommand(Intent("ACTION_RELOAD_TRIGGER_KEYS"), 0, 1)
            idle()
            val controller = requireNotNull(QuickBarService.serviceInstance)
            val onKeyEvent = QuickBarService::class.java.getDeclaredMethod(
                "onKeyEvent", KeyEvent::class.java
            ).apply { isAccessible = true }

            fun key(action: Int, keyCode: Int = KeyEvent.KEYCODE_F1) {
                assertTrue(onKeyEvent.invoke(service, KeyEvent(action, keyCode)) as Boolean)
            }

            fun close() {
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
                idle(500)
                assertNull(root(controller))
                assertTrue(QuickBarService.isRunning)
            }
            key(KeyEvent.ACTION_DOWN)
            key(KeyEvent.ACTION_UP)
            idle(500)
            assertEquals(bar.id, currentBarId(controller))
            close()
            key(KeyEvent.ACTION_DOWN)
            key(KeyEvent.ACTION_UP)
            idle(100)
            key(KeyEvent.ACTION_DOWN)
            key(KeyEvent.ACTION_UP)
            idle(100)
            assertEquals(second.id, currentBarId(controller))
            close()
            key(KeyEvent.ACTION_DOWN)
            idle(800)
            key(KeyEvent.ACTION_UP)
            assertEquals(third.id, currentBarId(controller))
        } finally {
            lifecycle.destroy()
        }
        assertFalse(QuickBarService.isRunning)
        assertNull(QuickBarService.serviceInstance)
    }
}
