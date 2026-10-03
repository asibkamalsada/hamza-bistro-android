package de.hamzabistro.printstation.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.CancelReason
import de.hamzabistro.printstation.core.Driver
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.PauseWhat
import de.hamzabistro.printstation.core.PaymentMethod
import de.hamzabistro.printstation.core.QueueGroup
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.core.StaffQueue
import de.hamzabistro.printstation.queue.StepFailure
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * The queue, for staff: what /orders shows, under the same three headings —
 * decide, cook, out — with the shift switch on top. On a tablet held
 * sideways the three headings stand side by side, as on a kitchen pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(viewModel: StaffViewModel, focus: StateFlow<String?>, onFocused: () -> Unit, onOpen: (StaffScreen) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val asked by focus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Coming back to the screen shows the queue as it is now; going away
    // sends what waits on its undo window, as /orders does.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }

    val view = LocalView.current
    DisposableEffect(state.prefs.keepAwake) {
        view.keepScreenOn = state.prefs.keepAwake
        onDispose { view.keepScreenOn = false }
    }

    StaffEvents(viewModel, snackbar)

    val actions = remember(viewModel) { Actions(context, viewModel) }
    val waiting = state.queue.orders.count { it.status == OrderStatus.NEW }
    // A driver's phone opens on "Fahrer"; any device can switch.
    var driving by rememberSaveable { mutableStateOf(viewModel.driverDevice) }
    // A tapped notification is about the queue.
    LaunchedEffect(asked) { if (asked != null) driving = false }
    val chosen by viewModel.chosen.collectAsStateWithLifecycle()
    val stopOrder by viewModel.stopOrder.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (waiting > 0) stringResource(R.string.queue_title_waiting, waiting) else stringResource(R.string.queue_title))
                },
                actions = { QueueMenu(onOpen) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Banners(state, viewModel, onOpen)
            val forDriver = state.queue.orders.count(Driver::isForDriver)
            PrimaryTabRow(selectedTabIndex = if (driving) 1 else 0, modifier = Modifier.padding(top = 8.dp)) {
                Tab(selected = !driving, onClick = { driving = false }, text = { Text(stringResource(R.string.queue_tab)) })
                Tab(selected = driving, onClick = { driving = true }, text = { Text(stringResource(R.string.driver_tab, forDriver)) })
            }
            when {
                !state.queue.loaded && state.queue.failingSince == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                driving -> DriverView(state, chosen, stopOrder, viewModel, actions)
                state.queue.orders.isEmpty() ->
                    Text(
                        stringResource(R.string.queue_empty),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> Groups(state, actions, asked, onFocused)
            }
        }
    }
}

/** What happened that the screen says once, in a snackbar: a step, a ticket, the shop switch. */
@Composable
fun StaffEvents(viewModel: StaffViewModel, snackbar: SnackbarHostState) {
    val event by viewModel.events.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(event) {
        val shown = event ?: return@LaunchedEffect
        snackbar.showSnackbar(
            when (shown) {
                is StaffEvent.Step ->
                    when (val failure = shown.failure) {
                        StepFailure.Moved -> resources.getString(R.string.order_moved)
                        StepFailure.NotDelayable -> resources.getString(R.string.order_not_delayable)
                        StepFailure.PaymentLocked -> resources.getString(R.string.payment_locked)
                        StepFailure.NotPackable -> resources.getString(R.string.order_not_packable)
                        is StepFailure.Failed -> resources.getString(R.string.step_failed, failure.reason)
                    }
                is StaffEvent.Printed -> resources.getString(R.string.printed_by_hand, shown.order)
                is StaffEvent.PrintFailed -> resources.getString(R.string.printer_failed, shown.reason)
                is StaffEvent.ShopFailed -> resources.getString(R.string.shop_failed, shown.reason)
                is StaffEvent.PreordersInside ->
                    resources.getQuantityString(R.plurals.shop_preorders_inside, shown.count, shown.count)
                is StaffEvent.DelayedAll ->
                    if (shown.count == 0) resources.getString(R.string.delay_all_none)
                    else resources.getQuantityString(R.plurals.delay_all_done, shown.count, shown.count, shown.minutes)
                is StaffEvent.DelayAllFailed -> resources.getString(R.string.delay_all_failed, shown.reason)
            }
        )
        viewModel.eventShown()
    }
}

@Composable
private fun Groups(state: StaffState, actions: OrderActions, focus: String?, onFocused: () -> Unit) {
    val groups = StaffQueue.group(state.queue.orders)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            // Side by side, each heading its own column, as on a pass.
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (group in QueueGroup.entries) {
                    val orders = groups.firstOrNull { it.first == group }?.second.orEmpty()
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        heading(group, orders.size)
                        cards(orders, state, actions, focus)
                    }
                }
            }
        } else {
            val list = rememberLazyListState()
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((group, orders) in groups) {
                    heading(group, orders.size)
                    cards(orders, state, actions, focus)
                }
            }
            // A tapped notification: scroll to the order it was about.
            LaunchedEffect(focus, state.queue.orders) {
                if (focus == null) return@LaunchedEffect
                var index = 0
                for ((_, orders) in groups) {
                    index++
                    val at = orders.indexOfFirst { it.id == focus }
                    if (at >= 0) {
                        list.animateScrollToItem(index + at)
                        break
                    }
                    index += orders.size
                }
                delay(FOCUS_MS)
                onFocused()
            }
        }
    }
}

private fun LazyListScope.heading(group: QueueGroup, count: Int) {
    item(key = "heading-$group") {
        Text(
            "${headingText(group)} · $count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

@Composable
private fun headingText(group: QueueGroup): String =
    stringResource(
        when (group) {
            QueueGroup.DECIDE -> R.string.queue_decide
            QueueGroup.COOK -> R.string.queue_cook
            QueueGroup.OUT -> R.string.queue_out
        }
    )

private fun LazyListScope.cards(orders: List<StaffOrder>, state: StaffState, actions: OrderActions, focus: String?) {
    items(orders, key = { it.id }) { order ->
        OrderCard(
            order = order,
            now = state.now,
            prep = state.queue.prep,
            prefs = state.prefs,
            pending = state.pending[order.id],
            busy = order.id in state.busy,
            canPrint = state.canPrint,
            actions = actions,
            busyMinutes = state.busyMinutes,
            focused = order.id == focus,
            packing = state.packing,
        )
    }
}

/**
 * What would stop this device hearing about the next order, one line each,
 * with the fix a tap away — the shift switched off above all.
 */
@Composable
private fun Banners(state: StaffState, viewModel: StaffViewModel, onOpen: (StaffScreen) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val askForNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.setOnShift(true) }
    val update = viewModel.update.collectAsStateWithLifecycle().value.available

    Column(modifier = Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopSwitch(state.shop, state.now, remember(viewModel) { ShopButtons(viewModel) }, onHours = { onOpen(StaffScreen.HOURS) })
        KitchenLoadLine(viewModel.kitchen.collectAsStateWithLifecycle().value, state.now)
        val delayingAll by viewModel.delayingAll.collectAsStateWithLifecycle()
        DelayAll(state.delayableAll.size, delayingAll, viewModel::delayAll)
        if (!state.prefs.onShift) {
            Banner(stringResource(R.string.shift_off_banner), warning = true) {
                Button(onClick = {
                    if (needsNotificationPermission(context)) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else viewModel.setOnShift(true)
                }) { Text(stringResource(R.string.shift_start)) }
            }
        } else {
            val missing = remember(checks) { missingForAlarm(context) }
            if (missing.isNotEmpty()) {
                Banner(stringResource(R.string.alarm_limited, missing.joinToString(", ") { resources.getString(it) }), warning = true) {}
            }
        }
        state.alarm.silencedUntil?.let {
            Banner(stringResource(R.string.silenced_until, Format.clock(it)), warning = false) {}
        }
        if (state.queue.notAllowed) Banner(stringResource(R.string.queue_not_staff), warning = true) {}
        // Orders priced on whatever postcode was typed: the detail, and the test, are in the settings.
        if (state.addressFailing) {
            Banner(stringResource(R.string.address_failing_banner), warning = true) {
                OutlinedButton(onClick = { onOpen(StaffScreen.SETTINGS) }) { Text(stringResource(R.string.settings)) }
            }
        }
        state.queue.failingSince?.let {
            Banner(stringResource(R.string.queue_offline_since, Format.clock(it)), warning = true) {
                OutlinedButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
            }
        }
        // Last: a newer build can wait until the orders are answered.
        update?.let { release ->
            Banner(stringResource(R.string.update_available, release.versionName), warning = false) {
                OutlinedButton(onClick = { context.openDownload(release.downloadUrl) }) { Text(stringResource(R.string.update_install)) }
            }
        }
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
private fun Banner(text: String, warning: Boolean, action: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
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

/** The permissions an alarm needs that this device has not given, as names to show. */
fun missingForAlarm(context: Context): List<Int> {
    val missing = mutableListOf<Int>()
    val notifications = context.getSystemService(NotificationManager::class.java)
    if (!notifications.areNotificationsEnabled()) missing += R.string.need_notifications
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !notifications.canUseFullScreenIntent()) {
        missing += R.string.need_full_screen
    }
    if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
        missing += R.string.need_background
    }
    return missing
}

fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * Where the queue leads: on a tablet, each a button; on a phone, behind one,
 * so the title keeps its room.
 */
@Composable
private fun QueueMenu(onOpen: (StaffScreen) -> Unit) {
    val entries =
        listOf(
            StaffScreen.HISTORY to R.string.history_title,
            StaffScreen.CASH_UP to R.string.cash_up_title,
            StaffScreen.REPORT to R.string.report_title,
            StaffScreen.MENU to R.string.menu_title,
            StaffScreen.HOURS to R.string.hours_title,
            StaffScreen.SETTINGS to R.string.settings,
        )
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    if (width >= WIDE) {
        for ((screen, label) in entries) TextButton(onClick = { onOpen(screen) }) { Text(stringResource(label)) }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.more_menu)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((screen, label) in entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        onOpen(screen)
                    },
                )
            }
        }
    }
}

/** The shop line's buttons, wired to the view model. */
internal class ShopButtons(private val viewModel: StaffViewModel) : ShopActions {
    override fun pause(minutes: Long, what: PauseWhat) = viewModel.pauseShop(minutes, what)

    override fun closeForToday(what: PauseWhat) = viewModel.closeShopForToday(what)

    override fun closeForGood(what: PauseWhat) = viewModel.closeShopForGood(what)

    override fun open() = viewModel.openShop()

    override fun busy(minutes: Int, duration: Duration?) = viewModel.busyShop(minutes, duration)

    override fun notBusy() = viewModel.notBusyShop()
}

/** The card's buttons, wired to the view model and to the phone's dialler and map. */
internal class Actions(private val context: Context, private val viewModel: StaffViewModel) : OrderActions {
    override fun accept(order: StaffOrder, minutes: Int) = viewModel.accept(order, minutes)

    override fun acceptScheduled(order: StaffOrder) = viewModel.acceptScheduled(order)

    override fun moveOn(order: StaffOrder) = viewModel.moveOn(order)

    override fun deliver(order: StaffOrder, payment: PaymentMethod) = viewModel.deliver(order, payment)

    override fun cancel(order: StaffOrder, reason: CancelReason?) = viewModel.cancel(order, reason)

    override fun delay(order: StaffOrder, minutes: Int) = viewModel.delay(order, minutes)

    override fun pack(order: StaffOrder) = viewModel.pack(order)

    override fun unpack(order: StaffOrder) = viewModel.unpack(order)

    override fun undo(order: StaffOrder) = viewModel.undo(order)

    override fun print(order: StaffOrder) = viewModel.print(order)

    /** The dialler with the number in it — not a call: the person decides. */
    override fun call(phone: String) {
        open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
    }

    override fun route(order: StaffOrder) {
        val prefs = viewModel.state.value.prefs
        link(StaffQueue.navigationUrl(order, prefs.navApp, prefs.travelMode))
    }

    /** A map link: a whole route, or the next stop. */
    fun link(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun open(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No dialler or map on a kitchen tablet: nothing to open.
        }
    }
}

private const val FOCUS_MS = 4_000L

/** From here on the queue's top bar has room for every button. */
private val WIDE = 720.dp
