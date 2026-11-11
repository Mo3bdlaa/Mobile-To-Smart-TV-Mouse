package io.github.mo3bdlaa.tvmouse.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import io.github.mo3bdlaa.tvmouse.BuildConfig
import io.github.mo3bdlaa.tvmouse.hid.HidManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Catches crashes, saves a report and shows [CrashActivity], which lets the user open a
 * pre-filled GitHub issue. If the screen can't be shown right away (for example the app was
 * in the background), the saved report is offered the next time the app opens.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildReport(thread, error)
                File(app.filesDir, FILE_NAME).writeText(report)
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .putExtra(CrashActivity.EXTRA_REPORT, report)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            } catch (_: Throwable) {
                // Never let the crash handler itself crash; fall back to the system handler.
                previous?.uncaughtException(thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    fun pendingReport(context: Context): String? =
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()

    fun clearPending(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun buildReport(thread: Thread, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val connection = runCatching { HidManager.state.value.status.name }.getOrDefault("unknown")
        return buildString {
            appendLine("Exception: ${error.javaClass.name}: ${error.message.orEmpty().take(200)}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            appendLine(deviceSummary())
            appendLine("Thread: ${thread.name}")
            appendLine("Connection: $connection")
            appendLine("Time: $time")
            appendLine()
            append(stack)
        }
    }

    fun deviceSummary(): String =
        "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
}
