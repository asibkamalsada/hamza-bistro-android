package de.hamzabistro.printstation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AddressRequest
import de.hamzabistro.printstation.core.AddressRequests
import de.hamzabistro.printstation.core.AddressRequestsState
import de.hamzabistro.printstation.core.PhoneNumbers
import de.hamzabistro.printstation.core.PhoneOrderError
import de.hamzabistro.printstation.core.SmsState
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What [problem] — a refusal's code, as the tablet noted it — means, in words. */
@Composable
internal fun problemText(problem: String): String =
    when (PhoneOrderError.of(problem)) {
        PhoneOrderError.OUTSIDE -> stringResource(R.string.address_problem_outside)
        PhoneOrderError.MINIMUM -> stringResource(R.string.address_problem_minimum)
        PhoneOrderError.UNCHECKED -> stringResource(R.string.address_problem_unchecked)
        PhoneOrderError.BASKET -> stringResource(R.string.address_problem_basket)
        else -> stringResource(R.string.address_problem_other, problem)
    }

internal fun dial(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.filterNot(Char::isWhitespace), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // A tablet without a dialler: the number is on the screen.
    }
}

/** What can be done to a link from any screen: sent again, tried again, deleted. The screen follows from the database. */
internal class LinkActions(private val graph: AppGraph) {
    fun resend(id: String) = change { graph.addressRequests.resend(id) }

    fun retry(id: String) = change { graph.addressRequests.noteProblem(id, null) }

    fun cancel(id: String) = change { graph.addressRequests.cancel(id) }

    private fun change(block: suspend () -> Unit) {
        graph.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.logger.warn("The address link could not be changed", e)
            }
            graph.addressWatch.refresh()
        }
    }
}

/**
 * Above the queue, while any basket waits for its caller's address: who,
 * what, and how far it got — the text, the address, a problem for a
 * person. Shown on every device, so nobody forgets one; placed by the
 * device that took it.
 */
@Composable
fun WaitingForAddress(modifier: Modifier = Modifier) {
    val graph = (LocalContext.current.applicationContext as PrintStationApp).graph
    val read by graph.liveAddressRequests.collectAsStateWithLifecycle(AddressRequestsState())
    val actions = remember(graph) { LinkActions(graph) }
    WaitingCards(AddressRequests.waiting(read.requests), actions::resend, actions::retry, actions::cancel, modifier)
}

@Composable
internal fun WaitingCards(
    waiting: List<AddressRequest>,
    onResend: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (waiting.isEmpty()) return
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<AddressRequest?>(null) }
    val now by produceState(Instant.now()) {
        while (true) {
            delay(TICK)
            value = Instant.now()
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.waiting_heading, waiting.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (request in waiting) {
                val problem = request.problem
                val expired = request.address == null && !request.createdAt.isAfter(now.minus(AddressRequests.OPEN_FOR.toJavaDuration()))
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = if (problem != null || expired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                        ),
                    modifier = Modifier.width(360.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            listOf(request.customerName, PhoneNumbers.national(request.phone)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(request.lines.joinToString(", ") { "${it.qty}× ${it.name}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        when {
                            problem != null ->
                                Text(stringResource(R.string.call_order_problem, problemText(problem)), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            request.address != null -> Text(stringResource(R.string.waiting_address, request.address?.line.orEmpty()))
                            expired -> Text(stringResource(R.string.waiting_expired), color = MaterialTheme.colorScheme.error)
                            request.sms == SmsState.SENT -> Text(stringResource(R.string.call_order_sms_sent))
                            request.sms == SmsState.FAILED ->
                                Text(stringResource(R.string.call_order_sms_failed, request.smsError.orEmpty()), color = MaterialTheme.colorScheme.error)
                            AddressRequests.unsent(request, now) -> Text(stringResource(R.string.call_order_sms_no_sender), color = MaterialTheme.colorScheme.error)
                            else -> Text(stringResource(R.string.call_order_sms_sending))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { dial(context, request.phone) }) { Text("📞") }
                            if (problem != null) TextButton(onClick = { onRetry(request.id) }) { Text(stringResource(R.string.call_order_retry)) }
                            if (problem == null && request.address == null && !expired && (request.sms == SmsState.FAILED || AddressRequests.unsent(request, now))) {
                                TextButton(onClick = { onResend(request.id) }) { Text(stringResource(R.string.call_order_sms_again)) }
                            }
                            TextButton(onClick = { deleting = request }) { Text(stringResource(R.string.waiting_delete)) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { request ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.waiting_delete_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleting = null
                        onDelete(request.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.waiting_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel_back)) } },
        )
    }
}

/** How often a line that depends on the time — "no phone has sent it" — is looked at again. */
internal val TICK = 5.seconds
