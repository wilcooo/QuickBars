package dev.trooped.tvquickbars.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.trooped.tvquickbars.BuildConfig
import dev.trooped.tvquickbars.services.QuickBarOverlayService
import dev.trooped.tvquickbars.services.QuickBarService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, local-only debug log. Never reads credentials or Home Assistant state. */
object QuickBarDiagnostics {
    private const val PREFS = "external_quickbar_diagnostics"
    private const val EVENTS = "events"
    private const val FAILURE = "failure"
    private const val TAG = "QuickBarDiagnostics"

    fun installCrashRecorder(context: Context) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Persist before Android terminates the process; never try to resume it.
            try { failure(app, "Uncaught exception on ${thread.name}", error) }
            catch (_: Throwable) { /* Preserve the original crash even if storage fails. */ }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        record(app, "Process started")
    }

    @Synchronized
    fun record(context: Context, event: String) {
        if (!BuildConfig.DEBUG) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val line = "$time ${sanitize(event).take(180)}"
        val recent = (prefs.getString(EVENTS, "").orEmpty().lines().filter { it.isNotEmpty() } + line)
            .takeLast(24).joinToString("\n")
        prefs.edit().putString(EVENTS, recent).commit()
        Log.i(TAG, line)
    }

    @Synchronized
    fun failure(context: Context, stage: String, error: Throwable) {
        if (!BuildConfig.DEBUG) return
        val text = sanitize("$stage\n${error.stackTraceToString()}").take(14000)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(FAILURE, text).commit()
        record(context, "FAILED: $stage (${error.javaClass.simpleName})")
        Log.e(TAG, text)
    }

    fun report(context: Context, compact: Boolean = false): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = prefs.getString(EVENTS, "").orEmpty().lines()
        val failure = prefs.getString(FAILURE, "No recorded exception").orEmpty()
        val details = if (compact && failure.length > 950) {
            failure.take(550) + "\n...\n" + failure.takeLast(400)
        } else failure
        return buildString {
            appendLine("QuickBars ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
            appendLine("Overlay permission: ${Settings.canDrawOverlays(context)}")
            appendLine("Accessibility bound: ${QuickBarService.isRunning}")
            appendLine("Overlay service running: ${QuickBarOverlayService.instance != null}")
            appendLine("\nLast exception:")
            appendLine(details)
            appendLine("\nRecent steps:")
            append((if (compact) events.takeLast(8) else events).joinToString("\n"))
        }
    }

    /** Remove URLs, bearer tokens, and long opaque values from exception messages. */
    internal fun sanitize(text: String): String = text
        .replace(Regex("(?i)\\b(?:https?|wss?|rtsp)://[^\\s]+"), "[URL removed]")
        .replace(Regex("(?i)\\bBearer\\s+[^\\s]+"), "Bearer [removed]")
        .replace(Regex("[A-Za-z0-9_+/=-]{48,}"), "[opaque value removed]")
}
