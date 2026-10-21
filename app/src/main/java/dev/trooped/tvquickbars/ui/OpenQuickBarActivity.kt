package dev.trooped.tvquickbars.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
                Log.e("OpenQuickBar", "Could not open QuickBar", e)
                Toast.makeText(this, R.string.external_quickbar_failed, Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }

    private fun openQuickBar() {
        if (intent.action != QuickBarRequest.ACTION_OPEN) return
        val bar = QuickBarRequest.resolve(QuickBarManager(this).loadQuickBars(),
            intent.getStringExtra(QuickBarRequest.EXTRA_ID),
            intent.getStringExtra(QuickBarRequest.EXTRA_NAME))
        if (bar == null) {
            Toast.makeText(this, R.string.external_quickbar_not_found, Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.external_quickbar_permission, Toast.LENGTH_LONG).show()
            return
        }
        if (QuickBarService.isRunning) {
            QuickBarService.serviceInstance?.openQuickBar(bar)
        } else {
            ContextCompat.startForegroundService(this,
                Intent(this, QuickBarOverlayService::class.java)
                    .setAction(QuickBarRequest.ACTION_OPEN)
                    .putExtra(QuickBarRequest.EXTRA_ID, bar.id))
        }
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
                val launch = Intent(this, OpenQuickBarActivity::class.java)
                    .setAction(QuickBarRequest.ACTION_OPEN)
                    .putExtra(QuickBarRequest.EXTRA_ID, bar.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
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
