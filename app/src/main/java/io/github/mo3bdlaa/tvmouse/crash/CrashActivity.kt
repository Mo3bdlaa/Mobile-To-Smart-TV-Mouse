package io.github.mo3bdlaa.tvmouse.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mo3bdlaa.tvmouse.MainActivity
import io.github.mo3bdlaa.tvmouse.ui.TvMouseTheme

/** Shown (in its own process) after a crash. Offers to report it on GitHub. */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val report = intent.getStringExtra(EXTRA_REPORT)
            ?: CrashReporter.pendingReport(this)
            ?: "No crash details were saved."
        CrashReporter.clearPending(this)

        setContent {
            TvMouseTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .safeDrawingPadding()
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("TV Mouse stopped unexpectedly", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Report it on GitHub so it can be tracked and fixed. A new issue opens " +
                                "with the details filled in, and you can review it before posting.",
                        )
                        Button(
                            onClick = { IssueReporter.openCrashIssue(this@CrashActivity, report) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Report on GitHub") }
                        OutlinedButton(onClick = { copy(report) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Copy crash details")
                        }
                        OutlinedButton(onClick = ::restart, modifier = Modifier.fillMaxWidth()) {
                            Text("Restart TV Mouse")
                        }
                        TextButton(onClick = ::finish, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }

    private fun copy(report: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("TV Mouse crash", report))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun restart() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_REPORT = "report"
    }
}
