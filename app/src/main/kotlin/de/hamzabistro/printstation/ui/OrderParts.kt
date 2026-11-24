package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hamzabistro.printstation.R

// What every screen an order is taken on is made of: the slim bar on top, the
// buttons along the bottom, a line that says how something went.

/**
 * One slim bar: ☰, where this is, and in the middle — on a narrow screen
 * below — what to know about it ([note]). [tint] colours it, so what it says
 * cannot be missed and takes no room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlimHeader(
    title: String,
    navigation: @Composable () -> Unit,
    note: String? = null,
    tint: Boolean = false,
    busy: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = if (tint) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (tint) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
            val wide = maxWidth >= WIDE_FROM
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Where the other screens' bars have it.
                    Box(Modifier.padding(start = 4.dp)) { navigation() }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (wide) Modifier else Modifier.weight(1f),
                    )
                    if (wide) HeaderNote(note.orEmpty(), Modifier.weight(1f).padding(horizontal = 16.dp))
                    action?.invoke()
                }
                if (!wide && note != null) HeaderNote(note, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                // The same height at rest, so nothing moves under a finger.
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else HorizontalDivider(thickness = 4.dp, color = MaterialTheme.colorScheme.surface)
            }
        }
    }
}

@Composable
private fun HeaderNote(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = modifier)
}

/** The buttons along the bottom: "Zurück" as wide as it needs, the step's own button the rest. */
@Composable
internal fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A button big enough to hit without looking twice. */
@Composable
internal fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false, tonal: Boolean = false) {
    val label: @Composable RowScope.() -> Unit = {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val big = modifier.heightIn(min = 64.dp)
    val padding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    when {
        outlined -> OutlinedButton(onClick = onClick, modifier = big, enabled = enabled, contentPadding = padding, content = label)
        tonal -> FilledTonalButton(onClick = onClick, modifier = big, enabled = enabled, contentPadding = padding, content = label)
        else -> Button(onClick = onClick, modifier = big, enabled = enabled, contentPadding = padding, content = label)
    }
}

/** A line that says how something went: green for done, red for wrong. */
@Composable
internal fun StatusLine(text: String, good: Boolean = false, bad: Boolean = false, soon: Boolean = false) {
    Text(
        text,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        color =
            when {
                bad -> MaterialTheme.colorScheme.error
                good -> toneColor(Tone.OK)
                soon -> toneColor(Tone.SOON)
                else -> MaterialTheme.colorScheme.onSurface
            },
    )
}

/** What went wrong; gone with a tap, or with the next thing done. */
@Composable
internal fun ProblemCard(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss = stringResource(R.string.till_dismiss)
    Surface(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "✕",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 56.dp).semantics { contentDescription = dismiss },
            )
        }
    }
}

/** Asked before something that cannot be taken back. */
@Composable
internal fun AskFirst(title: String, text: String?, yes: String, onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it, style = MaterialTheme.typography.titleMedium) } },
        confirmButton = {
            Button(
                onClick = onYes,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.heightIn(min = 52.dp),
            ) {
                Text(yes, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 52.dp)) {
                Text(stringResource(R.string.cancel_back), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}
