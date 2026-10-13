package de.hamzabistro.printstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.CallsState
import de.hamzabistro.printstation.core.IncomingCall
import de.hamzabistro.printstation.core.IncomingCalls
import de.hamzabistro.printstation.core.PhoneNumbers
import de.hamzabistro.printstation.core.RecentAddress
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Who is calling the shop's phone, over whatever screen is up: the number,
 * big, where it had its last delivery, and one big button that opens
 * "Telefon-Bestellung" with the number in it. On top of the screen rather
 * than above it, so no order moves under a finger when the phone rings.
 *
 * Nothing opens by itself. A banner goes when it is used, put away, or a
 * few minutes old; several callers stack, the latest on top. [newOrderOpen]
 * is whether an order form is the screen underneath: the button then only
 * takes the number, and only into an empty field.
 */
@Composable
fun CallBanners(newOrderOpen: Boolean, onNewOrder: () -> Unit, modifier: Modifier = Modifier, order: NewOrderViewModel = viewModel()) {
    val graph = (LocalContext.current.applicationContext as PrintStationApp).graph
    val read by graph.liveCalls.collectAsStateWithLifecycle(CallsState())
    CallBanners(read.calls, { phone -> graph.calls.recentAddresses(phone).firstOrNull() }, newOrderOpen, onNewOrder, order, modifier)
}

/** The banners for [calls] as read, wherever they were read from: [lastAddress] is asked once per caller. */
@Composable
internal fun CallBanners(
    calls: List<IncomingCall>,
    lastAddress: suspend (String) -> RecentAddress?,
    newOrderOpen: Boolean,
    onNewOrder: () -> Unit,
    order: NewOrderViewModel,
    modifier: Modifier = Modifier,
) {
    var dismissed by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Looked at again every few seconds, so a banner goes with nothing else happening — here, not for the whole app.
    val now by
        produceState(Instant.now()) {
            while (true) {
                delay(AGE_TICK)
                value = Instant.now()
            }
        }
    val shown = IncomingCalls.toShow(calls, dismissed, now)
    if (shown.isEmpty()) return
    val underneath = order.state.collectAsStateWithLifecycle().value
    val phoneTyped = underneath.draft.phone.isNotBlank()
    // The order underneath is saved, or waits on its link: this call is a new one.
    val fresh = !newOrderOpen || underneath.finished
    Column(modifier.statusBarsPadding().padding(12.dp).widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (call in shown) {
            key(call.id) {
                CallBanner(
                    call,
                    lastAddress,
                    // With a number typed in the order underneath, there is nothing to offer but putting the banner away.
                    action = if (fresh) R.string.call_take_order else if (!phoneTyped) R.string.call_take_number else null,
                    onAction = {
                        if (underneath.finished) order.reset()
                        order.takeNumber(call.phone)
                        dismissed = dismissed + call.id
                        onNewOrder()
                    },
                    onDismiss = { dismissed = dismissed + call.id },
                )
            }
        }
    }
}

@Composable
private fun CallBanner(call: IncomingCall, lastAddress: suspend (String) -> RecentAddress?, action: Int?, onAction: () -> Unit, onDismiss: () -> Unit) {
    // A caller the shop has not delivered to, or a lookup that fails, is a banner without the line.
    val address by
        produceState<RecentAddress?>(null, call.phone) {
            value =
                try {
                    lastAddress(call.phone)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
        }
    // Big, and with one big button: seen from across the kitchen, and hit without reading.
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 12.dp,
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.call_ringing), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.call_from, PhoneNumbers.national(call.phone)),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    address?.let { Text(stringResource(R.string.call_last_address, it.line), style = MaterialTheme.typography.headlineSmall) }
                }
                val putAway = stringResource(R.string.call_dismiss)
                TextButton(onClick = onDismiss, modifier = Modifier.size(72.dp).semantics { contentDescription = putAway }) {
                    Text("✕", style = MaterialTheme.typography.headlineMedium)
                }
            }
            if (action != null) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)) {
                    Text(stringResource(action), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private val AGE_TICK = 5.seconds
