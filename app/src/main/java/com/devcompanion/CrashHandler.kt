package com.devcompanion

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler : Thread.UncaughtExceptionHandler {
    private const val TAG = "CrashHandler"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: android.content.Context? = null

    fun install(context: android.content.Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        Log.e(TAG, "=== UNCAUGHT EXCEPTION ===\n$stackTrace")

        // Save to file
        saveCrashLog(stackTrace)

        // Launch CrashReportActivity on main thread
        appContext?.let { ctx ->
            try {
                val intent = Intent(ctx, CrashReportActivity::class.java).apply {
                    putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                ctx.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch CrashReportActivity", e)
            }
        }

        // Let the default handler finish (kills the process)
        defaultHandler?.uncaughtException(thread, throwable)
            ?: Runtime.getRuntime().exit(1)
    }

    private fun saveCrashLog(stackTrace: String) {
        try {
            val dir = File(appContext?.filesDir, "crash_logs")
            if (!dir.exists()) dir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash_$timestamp.txt")
            file.writeText(buildString {
                appendLine("=== DevCompanion Crash Log ===")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("Thread: ${Thread.currentThread().name}")
                appendLine()
                appendLine(stackTrace)
                appendLine()
                appendLine("=== Device Info ===")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            })
            Log.i(TAG, "Crash log saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
    }

    fun getLatestCrashLog(): String? {
        return try {
            val dir = File(appContext?.filesDir, "crash_logs")
            val file = dir.listFiles()
                ?.sortedByDescending { it.name }
                ?.firstOrNull()
            file?.readText()
        } catch (_: Exception) { null }
    }

    fun clearCrashLogs() {
        try {
            val dir = File(appContext?.filesDir, "crash_logs")
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}