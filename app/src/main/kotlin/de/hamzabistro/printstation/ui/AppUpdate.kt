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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.BuildConfig
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AppRelease
import de.hamzabistro.printstation.core.InstallState
import de.hamzabistro.printstation.core.UpdateState
import kotlin.math.roundToInt

/**
 * The APK in the browser, for when fetching it here did not work: the
 * browser downloads it, and Android's installer puts it over this version,
 * keeping the sign-in and the printer.
 */
fun Context.openDownload(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser on the device: the settings show the version to fetch by hand.
    }
}

/**
 * "Installieren": the app fetches the APK itself and opens Android's
 * installer over it (AppGraph.installer). While it is on its way the button
 * says how far, and cannot be tapped again.
 */
@Composable
fun InstallButton(release: AppRelease, install: InstallState, onInstall: (AppRelease) -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = { onInstall(release) }, enabled = install !is InstallState.Fetching, modifier = modifier) {
        val progress = (install as? InstallState.Fetching)?.progress
        Text(
            when {
                progress != null -> stringResource(R.string.update_fetching_percent, (progress * 100).roundToInt())
                install is InstallState.Fetching -> stringResource(R.string.update_fetching)
                else -> stringResource(R.string.update_install)
            }
        )
    }
}

/** Fetching it here did not work: said so, with the browser as the way that is left. */
@Composable
fun InstallFailed(release: AppRelease) {
    val context = LocalContext.current
    Text(stringResource(R.string.update_fetch_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    TextButton(onClick = { context.openDownload(release.downloadUrl) }) { Text(stringResource(R.string.update_in_browser)) }
}

/** The version running here, whether a newer one is out, and the button to fetch it. */
@Composable
fun AppSection(update: UpdateState, install: InstallState, onCheck: () -> Unit, onInstall: (AppRelease) -> Unit) {
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
            if (available != null) InstallButton(available, install, onInstall)
            OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.update_check)) }
        }
        if (available != null && install is InstallState.Failed) InstallFailed(available)
        Text(stringResource(R.string.update_hint), style = MaterialTheme.typography.bodySmall)
    }
}
