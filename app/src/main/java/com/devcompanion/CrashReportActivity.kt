package com.devcompanion

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.PrintWriter
import java.io.StringWriter

class CrashReportActivity : Activity() {
    companion object {
        const val EXTRA_STACK_TRACE = "stack_trace"
        private const val TAG = "CrashReport"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "CrashReportActivity opened")

        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: "No stack trace available"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "⚠️ DevCompanion Crashed"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(title)

        val scrollView = ScrollView(this)

        val errorText = TextView(this).apply {
            text = stackTrace
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        scrollView.addView(errorText)
        layout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        val copyButton = Button(this).apply {
            text = "Copy & Close"
            setOnClickListener {
                try {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash_log", stackTrace))
                } catch (_: Exception) {}
                finish()
            }
        }
        buttonBar.addView(copyButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        val shareButton = Button(this).apply {
            text = "Share"
            setOnClickListener {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, stackTrace)
                    }
                    startActivity(android.content.Intent.createChooser(intent, "Share crash log"))
                } catch (_: Exception) {}
            }
        }
        buttonBar.addView(shareButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        layout.addView(buttonBar)

        setContentView(layout)
    }
}