package io.github.mo3bdlaa.tvmouse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mo3bdlaa.tvmouse.BuildConfig
import io.github.mo3bdlaa.tvmouse.crash.CrashReporter
import io.github.mo3bdlaa.tvmouse.crash.IssueReporter
import io.github.mo3bdlaa.tvmouse.update.Updater
import kotlinx.coroutines.launch

/** Offers a newer GitHub release, downloads it and opens the system installer. */
@Composable
fun UpdateDialog(release: Updater.Release, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    val downloading = progress != null

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Update available: ${release.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You have ${BuildConfig.VERSION_NAME}.")
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
                progress?.let { value ->
                    if (value >= 0f) {
                        LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = {
                if (!Updater.canInstall(context)) {
                    runCatching { Updater.openInstallPermissionSettings(context) }
                    onMessage("Allow TV Mouse to install updates, then tap Update again.")
                    return@TextButton
                }
                scope.launch {
                    progress = 0f
                    try {
                        val apk = Updater.download(context, release) { progress = it }
                        Updater.install(context, apk)
                        onDismiss()
                    } catch (e: Exception) {
                        onMessage("Update failed: ${e.message}")
                    } finally {
                        progress = null
                    }
                }
            }) { Text("Update") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("Later") }
        },
    )
}

/** Asks to report a crash that happened last time (when the crash screen couldn't open). */
@Composable
fun PendingCrashDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.pendingReport(context)) }
    val current = report ?: return
    fun close() {
        CrashReporter.clearPending(context)
        report = null
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("TV Mouse crashed last time") },
        text = { Text("Do you want to report it on GitHub so it can be fixed? You can review the report before posting.") },
        confirmButton = {
            TextButton(onClick = {
                IssueReporter.openCrashIssue(context, current)
                close()
            }) { Text("Report") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Not now") } },
    )
}
