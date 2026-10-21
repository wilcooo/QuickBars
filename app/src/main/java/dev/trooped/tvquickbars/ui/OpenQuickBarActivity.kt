package dev.trooped.tvquickbars.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.Bundle
import android.provider.Settings
import dev.trooped.tvquickbars.utils.QuickBarDiagnostics
import dev.trooped.tvquickbars.utils.PermissionUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.persistence.QuickBarManager
import dev.trooped.tvquickbars.services.QuickBarOverlayService
import dev.trooped.tvquickbars.services.QuickBarRequest
import dev.trooped.tvquickbars.services.QuickBarService

/** Shortcut picker and brief, translucent entry point for cold overlay launches. */
class OpenQuickBarActivity : Activity() {
    private var handled = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        QuickBarDiagnostics.record(this, "Launch timed out waiting for overlay service")
        showFailure(R.string.external_quickbar_timeout)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showFailure(message: Int, permission: Boolean = false) {
        handler.removeCallbacks(timeout)
        if (isFinishing || isDestroyed) return
        val dialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setNeutralButton(R.string.external_diagnostics_button) { _, _ ->
                startActivity(Intent(this, QuickBarDiagnosticsActivity::class.java))
                finish()
            }
            .setOnCancelListener { finish() }
        if (permission) dialog.setPositiveButton(R.string.go_to_settings) { _, _ ->
            PermissionUtils.navigateToOverlaySettings(this)
            finish()
        }
        else dialog.setPositiveButton(android.R.string.ok) { _, _ -> finish() }
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            handled = true
            createShortcut()
        }
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        // Launch from a resumed activity, rather than a background broadcast receiver.
        window.decorView.post {
            try {
                openQuickBar()
            } catch (e: RuntimeException) {
                QuickBarDiagnostics.failure(this, "Launch activity", e)
                showFailure(R.string.external_quickbar_failed)
            }
        }
    }

    private fun openQuickBar() {
        if (intent.action != QuickBarRequest.ACTION_OPEN) { finish(); return }
        QuickBarDiagnostics.record(this, "Open requested; overlay permission=${Settings.canDrawOverlays(this)}")
        val bar = QuickBarRequest.resolve(QuickBarManager(this).loadQuickBars(),
            intent.getStringExtra(QuickBarRequest.EXTRA_ID),
            intent.getStringExtra(QuickBarRequest.EXTRA_NAME))
        if (bar == null) {
            QuickBarDiagnostics.record(this, "Selector did not match one enabled QuickBar")
            showFailure(R.string.external_quickbar_not_found)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showFailure(R.string.external_quickbar_permission, permission = true)
            return
        }
        if (QuickBarService.isRunning) {
            QuickBarDiagnostics.record(this, "Using bound accessibility host")
            QuickBarService.serviceInstance?.openQuickBar(bar)
            finish()
        } else {
            QuickBarDiagnostics.record(this, "Starting on-demand overlay service")
            handler.postDelayed(timeout, 10_000)
            val result = object : ResultReceiver(handler) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    handler.removeCallbacks(timeout)
                    if (isFinishing || isDestroyed) return
                    if (resultCode == Activity.RESULT_OK) finish()
                    else showFailure(R.string.external_quickbar_failed)
                }
            }
            ContextCompat.startForegroundService(this,
                Intent(this, QuickBarOverlayService::class.java)
                    .setAction(QuickBarRequest.ACTION_OPEN)
                    .putExtra(QuickBarRequest.EXTRA_ID, bar.id)
                    .putExtra(QuickBarRequest.EXTRA_RESULT, result))
        }
    }

    companion object {
        fun createOpenIntent(context: Context, id: String) =
            Intent(context, OpenQuickBarActivity::class.java)
                .setAction(QuickBarRequest.ACTION_OPEN)
                .putExtra(QuickBarRequest.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    @Suppress("DEPRECATION")
    private fun createShortcut() {
        val bars = QuickBarManager(this).loadQuickBars().filter { it.isEnabled }
        if (bars.isEmpty()) {
            Toast.makeText(this, R.string.external_quickbar_empty, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.external_quickbar_choose)
            .setItems(bars.map { it.name }.toTypedArray()) { _, index ->
                val bar = bars[index]
                val launch = createOpenIntent(this, bar.id)
                setResult(RESULT_OK, Intent()
                    .putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch)
                    .putExtra(Intent.EXTRA_SHORTCUT_NAME, bar.name)
                    .putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_icon)))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
