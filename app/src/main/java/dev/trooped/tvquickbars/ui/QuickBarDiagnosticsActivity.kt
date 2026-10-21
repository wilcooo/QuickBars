package dev.trooped.tvquickbars.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.utils.QuickBarDiagnostics

/** Reports stay on the TV until the user copies, scans, or saves one. */
class QuickBarDiagnosticsActivity : Activity() {
    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val padding = (24 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(24, 24, 24))
        }
        root.addView(TextView(this).apply {
            setText(R.string.external_diagnostics_title)
            textSize = 23f
            setTextColor(Color.WHITE)
        })
        reportView = TextView(this).apply {
            text = QuickBarDiagnostics.report(this@QuickBarDiagnosticsActivity)
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val scroll = ScrollView(this).apply { addView(reportView); isFocusable = true }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this)
        fun button(label: Int, action: () -> Unit) {
            buttons.addView(Button(this).apply { setText(label); setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        button(R.string.external_diagnostics_refresh) { reportView.text = QuickBarDiagnostics.report(this) }
        button(R.string.external_diagnostics_qr) { showQr() }
        button(R.string.external_diagnostics_copy) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("QuickBars diagnostics", QuickBarDiagnostics.report(this)))
            Toast.makeText(this, R.string.external_diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
        button(R.string.external_diagnostics_save) {
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "QuickBars-diagnostics.txt"), SAVE_REPORT)
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.external_diagnostics_save_failed, Toast.LENGTH_LONG).show()
            }
        }
        root.addView(buttons)
        setContentView(root)
    }

    private fun showQr() {
        try {
            var text = QuickBarDiagnostics.report(this, compact = true)
            while (text.toByteArray(Charsets.UTF_8).size > 2200) text = text.dropLast(64)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
            }
            val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            val size = (resources.displayMetrics.heightPixels * 0.68f).toInt()
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                setBackgroundColor(Color.WHITE)
                contentDescription = getString(R.string.external_diagnostics_qr_help)
            }
            val container = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(image, LinearLayout.LayoutParams(size, size))
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.external_diagnostics_qr_help)
                .setView(container).setPositiveButton(android.R.string.ok, null).show()
        } catch (e: Exception) {
            QuickBarDiagnostics.failure(this, "Diagnostic QR", e)
            Toast.makeText(this, R.string.external_diagnostics_qr_failed, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Legacy activity result is sufficient for this standalone TV screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SAVE_REPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            requireNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use {
                it.write(QuickBarDiagnostics.report(this))
            }
            Toast.makeText(this, R.string.external_diagnostics_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            QuickBarDiagnostics.failure(this, "Save diagnostic report", e)
            Toast.makeText(this, R.string.external_diagnostics_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object { private const val SAVE_REPORT = 1 }
}
