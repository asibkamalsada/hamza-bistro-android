package de.hamzabistro.printstation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.BuildConfig
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.UpdateState

/**
 * The APK in the browser: it downloads it, and Android's installer puts it
 * over this version, keeping the sign-in and the printer. No install from
 * the app itself — Android would ask the person either way.
 */
fun Context.openDownload(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser on the device: the settings show the version to fetch by hand.
    }
}

/** The version running here, whether a newer one is out, and the button to fetch it. */
@Composable
fun AppSection(update: UpdateState, onCheck: () -> Unit) {
    val context = LocalContext.current
    Section(stringResource(R.string.section_app)) {
        Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME))
        val available = update.available
        val checkedAt = update.checkedAt
        when {
            available != null -> {
                Text(stringResource(R.string.update_available, available.versionName), style = MaterialTheme.typography.titleSmall)
                if (available.changes.isNotEmpty()) {
                    Text(stringResource(R.string.update_changes), style = MaterialTheme.typography.labelLarge)
                    Text(available.changes.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
                }
            }
            checkedAt != null && !update.failing -> Text(stringResource(R.string.update_current, Format.clock(checkedAt)))
            else -> Unit
        }
        if (update.failing) {
            Text(stringResource(R.string.update_failing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (available != null) {
                Button(onClick = { context.openDownload(available.downloadUrl) }) { Text(stringResource(R.string.update_install)) }
            }
            OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.update_check)) }
        }
        Text(stringResource(R.string.update_hint), style = MaterialTheme.typography.bodySmall)
    }
}
