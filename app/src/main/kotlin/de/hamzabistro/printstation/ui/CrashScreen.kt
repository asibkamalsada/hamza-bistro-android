package de.hamzabistro.printstation.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R

/**
 * What the app said when it last closed by itself (CrashLog), with
 * "Kopieren" to send it on and "Weiter" to the app. Shown on its own, before
 * the screens that may have crashed, so it is always seen.
 */
@Composable
fun CrashScreen(trace: String, onContinue: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.crash_text))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Hamza Team crash", trace))
                    copied = true
                }) {
                    Text(stringResource(if (copied) R.string.crash_copied else R.string.crash_copy))
                }
                Button(onClick = onContinue) { Text(stringResource(R.string.crash_continue)) }
            }
            Text(
                trace,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
            )
        }
    }
}
