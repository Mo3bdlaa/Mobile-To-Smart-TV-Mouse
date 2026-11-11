package io.github.mo3bdlaa.tvmouse.crash

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.mo3bdlaa.tvmouse.BuildConfig
import java.net.URLEncoder

/** Opens a pre-filled "new issue" page on GitHub. The user reviews it before posting. */
object IssueReporter {

    // Keep the URL short enough for browsers and GitHub; the full report can be copied.
    private const val MAX_REPORT_CHARS = 3000

    fun openCrashIssue(context: Context, report: String) {
        val firstLine = report.lineSequence().firstOrNull().orEmpty().removePrefix("Exception: ")
        val exceptionName = firstLine.substringBefore(": ").substringAfterLast('.')
        val message = firstLine.substringAfter(": ", "").trim()
        val title = ("Crash: $exceptionName" + if (message.isNotEmpty()) ": $message" else "").take(100)
        val body = buildString {
            appendLine("**What were you doing when it crashed?**")
            appendLine()
            appendLine("<!-- Describe what you tapped or what was happening. -->")
            appendLine()
            appendLine("**Crash report**")
            appendLine("```")
            appendLine(report.take(MAX_REPORT_CHARS))
            if (report.length > MAX_REPORT_CHARS) appendLine("… (truncated)")
            appendLine("```")
        }
        open(context, title, body, "crash")
    }

    fun openBugIssue(context: Context) {
        val body = buildString {
            appendLine("**What happened?**")
            appendLine()
            appendLine("**What did you expect?**")
            appendLine()
            appendLine("**TV brand / model:**")
            appendLine()
            appendLine("---")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(CrashReporter.deviceSummary())
        }
        open(context, "", body, "bug")
    }

    private fun open(context: Context, title: String, body: String, label: String) {
        val url = "https://github.com/${BuildConfig.GITHUB_REPO}/issues/new" +
            "?labels=${encode(label)}&title=${encode(title)}&body=${encode(body)}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Many TVs have no browser. Report from the phone instead.
            Toast.makeText(context, "No web browser here. Report the problem from your phone.", Toast.LENGTH_LONG).show()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
