package de.hamzabistro.printstation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.FailedEmail
import de.hamzabistro.printstation.core.Kitchen
import de.hamzabistro.printstation.core.KitchenSlot
import de.hamzabistro.printstation.core.ShopHours
import de.hamzabistro.printstation.core.ShopState
import de.hamzabistro.printstation.core.StaffQueue
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Everything the queue says above its orders (android#40), read off the
 * view model once: plain, so a test can draw any of it.
 */
data class QueueStatus(
    val shop: ShopView = ShopView(),
    val now: Instant = Instant.now(),
    val kitchen: List<KitchenSlot>? = null,
    /** What "Alle +15 Min." would move. */
    val delayable: Int = 0,
    val delayingAll: Boolean = false,
    val onShift: Boolean = true,
    val notStaff: Boolean = false,
    val offlineSince: Instant? = null,
    /** The permissions the alarm misses, as names; only asked while on shift. */
    val alarmMissing: List<String> = emptyList(),
    val silencedUntil: Instant? = null,
    val addressFailing: Boolean = false,
    val failedEmail: FailedEmail? = null,
    val notifyFailingSince: Instant? = null,
) {
    /** What keeps the queue from working at all: a slim bar each, always above the list. */
    val blocking: Boolean
        get() = !onShift || notStaff || offlineSince != null

    /** "⚠ N Hinweise": everything else worth knowing, folded into one chip. */
    val hints: Int
        get() = listOf(onShift && alarmMissing.isNotEmpty(), silencedUntil != null, addressFailing, failedEmail != null, notifyFailingSince != null).count { it }
}

/** What the strip, its bars and its sheet do. */
class StatusActions(
    val shop: ShopActions,
    val startShift: () -> Unit,
    val retry: () -> Unit,
    val delayAll: () -> Unit,
    /** The email warning tapped: its order. */
    val openOrder: (Long) -> Unit,
    val open: (StaffScreen) -> Unit,
)

/** Which part of the sheet comes first: the shop's controls, or the warnings. */
private enum class SheetFocus {
    SHOP,
    WARNINGS,
}

/**
 * The top of the queue, kept small so the orders get the screen: one strip
 * — the shop's state, the kitchen's quarter hour, "⚠ N Hinweise" — and a
 * slim bar for each state that stops the queue working. A tap on any of it
 * opens a sheet with the full shop switch, the hour's load line, "Alle +15
 * Min." and every warning in full, each with its button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueStatusTop(status: QueueStatus, actions: StatusActions) {
    var sheet by remember { mutableStateOf<SheetFocus?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusStrip(status, onShop = { sheet = SheetFocus.SHOP }, onHints = { sheet = SheetFocus.WARNINGS })
        if (!status.onShift) {
            Bar(stringResource(R.string.bar_shift_off), onClick = { sheet = SheetFocus.WARNINGS }) {
                TextButton(onClick = actions.startShift) { Text(stringResource(R.string.shift_start)) }
            }
        }
        if (status.notStaff) Bar(stringResource(R.string.bar_not_staff), onClick = { sheet = SheetFocus.WARNINGS }) {}
        status.offlineSince?.let {
            Bar(stringResource(R.string.bar_offline, Format.clock(it)), onClick = { sheet = SheetFocus.WARNINGS }) {
                TextButton(onClick = actions.retry) { Text(stringResource(R.string.retry)) }
            }
        }
    }

    val focus = sheet ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // A button that leads off the queue, or to an order: the sheet goes first.
    val close = { then: () -> Unit ->
        scope.launch { state.hide() }.invokeOnCompletion { sheet = null }
        then()
    }
    ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val controls = @Composable {
                ShopSwitch(status.shop, status.now, actions.shop, onHours = { close { actions.open(StaffScreen.HOURS) } })
                KitchenLoadLine(status.kitchen, status.now)
                DelayAll(status.delayable, status.delayingAll, actions.delayAll)
            }
            val warnings = @Composable { Warnings(status, actions, close) }
            if (focus == SheetFocus.WARNINGS) {
                warnings()
                controls()
            } else {
                controls()
                warnings()
            }
        }
    }
}

/** "Offen · Küche ■■■□ · ⚠ 2 Hinweise ▾": about one line, the whole of it a tap to the sheet. */
@Composable
private fun StatusStrip(status: QueueStatus, onShop: () -> Unit, onHints: () -> Unit) {
    val resources = LocalResources.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = stringResource(R.string.strip_open_label), onClick = onShop)
                .testTag(STRIP_TAG)
                .heightIn(min = 40.dp)
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hours = status.shop.hours
        val shopState = hours?.state(status.now)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color =
                when (shopState) {
                    ShopState.CLOSED -> MaterialTheme.colorScheme.errorContainer
                    ShopState.OPEN -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            contentColor = if (shopState == ShopState.CLOSED) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                shopChip(hours, status.now),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (shopState == ShopState.CLOSED) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The quarter hour now; the next hour's four are in the sheet.
        Kitchen.line(status.kitchen, status.now).firstOrNull()?.let { slot ->
            Text(
                stringResource(R.string.strip_kitchen, slot.bar),
                style = MaterialTheme.typography.labelLarge,
                color = if (slot.full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (slot.full) FontWeight.Bold else null,
                maxLines = 1,
                modifier =
                    Modifier.semantics {
                        contentDescription = resources.getString(R.string.kitchen_load_slot, Format.clock(slot.slot), slot.dishes, slot.capacity)
                    },
            )
        }
        if (status.hints > 0) {
            Surface(onClick = onHints, shape = RoundedCornerShape(8.dp), color = AMBER_LIGHT.takeUnless { isSystemInDarkTheme() } ?: AMBER_DARK) {
                Text(
                    pluralStringResource(R.plurals.strip_hints, status.hints, status.hints),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** "Offen", "Offen · Viel los +15", "Pause bis 19:30", "Geschlossen bis 18:00", "Außerhalb der Zeiten". */
@Composable
private fun shopChip(hours: ShopHours?, now: Instant): String {
    if (hours == null) return stringResource(R.string.strip_shop)
    return when (hours.state(now)) {
        ShopState.CLOSED -> {
            val reopens = hours.reopensAt(now)
            val today = now.atZone(AlarmPolicy.LEIPZIG).toLocalDate()
            if (reopens != null && reopens.atZone(AlarmPolicy.LEIPZIG).toLocalDate() == today) {
                stringResource(R.string.strip_closed_until, Format.clock(reopens))
            } else {
                stringResource(R.string.strip_closed)
            }
        }
        ShopState.OUTSIDE -> stringResource(R.string.strip_outside)
        ShopState.OPEN -> {
            val pause = hours.deliveryPauseAt(now)
            val base =
                if (pause == null) stringResource(R.string.strip_open)
                else pause.until?.let { stringResource(R.string.strip_paused_until, Format.clock(it)) } ?: stringResource(R.string.strip_paused)
            hours.busyAt(now)?.let { stringResource(R.string.strip_busy, base, it.minutes) } ?: base
        }
    }
}

/** A state that stops the queue working, in one line: the sheet has it in full. */
@Composable
private fun Bar(text: String, onClick: () -> Unit, action: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 40.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            action()
        }
    }
}

/**
 * Every warning in full, each with its fix — the slim bars' too: what would
 * stop this device hearing about the next order, the shift switched off
 * above all.
 */
@Composable
private fun Warnings(status: QueueStatus, actions: StatusActions, close: (() -> Unit) -> Unit) {
    if (!status.blocking && status.hints == 0) return
    Text(stringResource(R.string.strip_hints_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (!status.onShift) {
        Banner(stringResource(R.string.shift_off_banner), warning = true) {
            Button(onClick = actions.startShift) { Text(stringResource(R.string.shift_start)) }
        }
    } else if (status.alarmMissing.isNotEmpty()) {
        Banner(stringResource(R.string.alarm_limited, status.alarmMissing.joinToString(", ")), warning = true) {}
    }
    status.silencedUntil?.let { Banner(stringResource(R.string.silenced_until, Format.clock(it)), warning = false) {} }
    if (status.notStaff) Banner(stringResource(R.string.queue_not_staff), warning = true) {}
    // Orders priced on whatever postcode was typed: the detail, and the test, are in the settings.
    if (status.addressFailing) {
        Banner(stringResource(R.string.address_failing_banner), warning = true) {
            OutlinedButton(onClick = { close { actions.open(StaffScreen.SETTINGS) } }) { Text(stringResource(R.string.settings)) }
        }
    }
    status.offlineSince?.let {
        Banner(stringResource(R.string.queue_offline_since, Format.clock(it)), warning = true) {
            OutlinedButton(onClick = actions.retry) { Text(stringResource(R.string.retry)) }
        }
    }
    // The monitoring (hamza-bistro-web#92): an email that did not go out, and the phones' webhook failing.
    status.failedEmail?.let { failed ->
        val at = failed.failedAt?.let(Format::clock) ?: ""
        Banner(
            stringResource(R.string.ops_email_failed, failed.orderNumber, at, failed.error),
            warning = true,
            onClick = { close { actions.openOrder(failed.orderNumber) } },
        ) {}
    }
    status.notifyFailingSince?.let {
        Banner(stringResource(R.string.ops_notify_failing, Format.clock(it)), warning = false, amber = true) {}
    }
}

/**
 * "Alle +15 Min.", beside busy mode, for an evening that has gone wrong:
 * every promise already made moves later at once, where busy mode moves the
 * ones still to be made. Only while there is something to move, and only
 * after asking — it emails every one of those customers.
 */
@Composable
private fun DelayAll(count: Int, working: Boolean, onConfirm: () -> Unit) {
    // Forgotten once there is nothing left to move, so it does not pop up again later.
    var asking by remember(count == 0) { mutableStateOf(false) }
    if (count == 0) return
    val minutes = StaffQueue.DELAY_ALL_MINUTES
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = { asking = true }, enabled = !working) { Text(stringResource(R.string.delay_all, minutes)) }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            text = { Text(pluralStringResource(R.plurals.delay_all_confirm, count, count, minutes)) },
            confirmButton = {
                Button(onClick = {
                    asking = false
                    onConfirm()
                }) { Text(stringResource(R.string.delay_all_yes)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel_back)) } },
        )
    }
}

@Composable
private fun Banner(
    text: String,
    warning: Boolean,
    amber: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        warning -> MaterialTheme.colorScheme.errorContainer
                        amber -> if (isSystemInDarkTheme()) AMBER_DARK else AMBER_LIGHT
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, modifier = Modifier.weight(1f), color = if (warning) MaterialTheme.colorScheme.onErrorContainer else Color.Unspecified)
            action()
        }
    }
}

/** "Benachrichtigungen gestört": a warning, not yet an emergency — the app still rings. */
private val AMBER_LIGHT = Color(0xFFFFE0B2)
private val AMBER_DARK = Color(0xFF5D3A00)

/**
 * [top] above [content], scrolling away as the content scrolls up and back
 * once it is at its start again — on the phone's list, the tablet's three
 * columns and the driver's view alike, so each gets the whole height. Shown
 * again whenever [reveal] changes: a new blocking bar is not missed.
 */
@Composable
fun CollapsingTop(reveal: Any?, top: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val area = remember { TopArea() }
    LaunchedEffect(reveal) { area.offset = 0f }
    Layout(contents = listOf(top, content), modifier = modifier.fillMaxSize().clipToBounds().nestedScroll(area)) { (tops, bodies), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val placedTop = tops.map { it.measure(loose.copy(maxHeight = Constraints.Infinity)) }
        val height = placedTop.maxOfOrNull { it.height } ?: 0
        area.height = height
        val shift = area.offset.roundToInt().coerceIn(-height, 0)
        val shown = height + shift
        val rest = (constraints.maxHeight - shown).coerceAtLeast(0)
        val placedBody = bodies.map { it.measure(loose.copy(minHeight = rest, maxHeight = rest)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placedTop.forEach { it.place(0, shift) }
            placedBody.forEach { it.place(0, shown) }
        }
    }
}

/** How far [CollapsingTop]'s top has gone: 0 shown, −height gone. */
private class TopArea : NestedScrollConnection {
    var height = 0
    var offset by mutableFloatStateOf(0f)

    private fun move(dy: Float): Offset {
        val before = offset
        offset = (offset + dy).coerceIn(-height.toFloat(), 0f)
        return Offset(0f, offset - before)
    }

    // Up: the top goes first, then the list. Down: the list first, the top once it is at its start.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = if (available.y < 0) move(available.y) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0) move(available.y) else Offset.Zero
}

/** The strip, for tests. */
const val STRIP_TAG = "queue-status-strip"
