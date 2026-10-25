package app.yttranscribe

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(shared ?: "") }
            }
        }
    }
}

@Composable
private fun Screen(initialUrl: String) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf(initialUrl) }
    var stamps by remember { mutableStateOf(true) }
    val state by Transcriber.state.collectAsState()
    val segments by Transcriber.segments.collectAsState()
    val working = state is TState.Working

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val title = (state as? TState.Done)?.title ?: "transcript"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(Transcriber.render(segments, stamps))
            }
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("YouTube link") },
            singleLine = true, enabled = !working,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !working && url.isNotBlank(),
                onClick = {
                    ContextCompat.startForegroundService(
                        ctx, Intent(ctx, TranscribeService::class.java).putExtra(TranscribeService.EXTRA_URL, url)
                    )
                }
            ) { Text("Transcribe") }
            if (working) {
                OutlinedButton(onClick = {
                    ctx.startService(Intent(ctx, TranscribeService::class.java).setAction(TranscribeService.ACTION_CANCEL))
                }) { Text("Cancel") }
            }
        }

        when (val s = state) {
            is TState.Working -> {
                val pct = s.progress?.let { " ${(it * 100).toInt()}%" } ?: ""
                Text(s.stage + pct)
                if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is TState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            is TState.Done -> Text(s.title, style = MaterialTheme.typography.titleSmall)
            TState.Idle -> {}
        }

        if (segments.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = stamps, onCheckedChange = { stamps = it })
                Text("Timestamps")
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(Transcriber.render(segments, stamps))) }) { Text("Copy") }
                OutlinedButton(onClick = { save.launch(title.replace(Regex("""[\\/:*?"<>|]"""), "_") + ".txt") }) { Text("Save") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(segments) { seg ->
                    Text(if (stamps) "[${Transcriber.fmtTime(seg.startMs)}] ${seg.text}" else seg.text)
                }
            }
        }
    }
}
