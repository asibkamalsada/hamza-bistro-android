package de.hamzabistro.printstation.alarm

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.CancelReason
import de.hamzabistro.printstation.core.PaymentMethod
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.ui.AppTheme
import de.hamzabistro.printstation.ui.Format
import de.hamzabistro.printstation.ui.MainActivity
import de.hamzabistro.printstation.ui.NoShowReset
import de.hamzabistro.printstation.ui.OrderActions
import de.hamzabistro.printstation.ui.OrderCard
import de.hamzabistro.printstation.ui.StaffViewModel

/**
 * The alarm, over the lock screen and with the screen switched on, like an
 * incoming call: the order that has waited longest, what it is and what it
 * comes to, and the buttons to accept it there and then — with the same
 * undo window as the queue. Who ordered it and where they live are not
 * shown here; they are in the app, behind the lock screen.
 *
 * It goes by itself once nothing is waiting any more — accepted here, on
 * another phone, on the website or in Telegram.
 */
class AlarmActivity : ComponentActivity() {
    private val staff: StaffViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        setContent { AppTheme { AlarmScreen(staff, onDone = ::finish, onOpenApp = ::openApp) } }
    }

    /** The queue, after the lock screen: the rest of the order is there. */
    private fun openApp(order: StaffOrder?) {
        val open = {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .apply { if (order != null) putExtra(MainActivity.EXTRA_ORDER, order.id) }
            )
            finish()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return open()
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open()
            },
        )
    }
}

@Composable
private fun AlarmScreen(staff: StaffViewModel, onDone: () -> Unit, onOpenApp: (StaffOrder?) -> Unit) {
    val state by staff.state.collectAsStateWithLifecycle()
    val waiting = state.alarm.waiting
    // What was just tapped stays in view with its undo bar until it is
    // sent; then the next order waiting, if there is one.
    val shown = (state.pending.values.map { it.order } + waiting.take(1)).distinctBy { it.id }
    val order = shown.lastOrNull()

    // Nothing waits any more: answered somewhere. A moment's grace for the
    // first read, which comes back empty before it comes back.
    LaunchedEffect(state.queue.loaded, waiting.isEmpty(), state.pending.isEmpty()) {
        if (state.queue.loaded && waiting.isEmpty() && state.pending.isEmpty()) onDone()
    }

    val actions =
        object : OrderActions {
            override fun accept(order: StaffOrder, minutes: Int) = staff.accept(order, minutes)

            override fun acceptScheduled(order: StaffOrder) = staff.acceptScheduled(order)

            override fun moveOn(order: StaffOrder) = staff.moveOn(order)

            override fun deliver(order: StaffOrder, payment: PaymentMethod) = staff.deliver(order, payment)

            override fun cancel(order: StaffOrder, reason: CancelReason?) = staff.cancel(order, reason)

            override fun resetNoShows(order: StaffOrder, done: (NoShowReset) -> Unit) = staff.resetNoShows(order, done)

            override fun delay(order: StaffOrder, minutes: Int) = staff.delay(order, minutes)

            override fun pack(order: StaffOrder) = staff.pack(order)

            override fun unpack(order: StaffOrder) = staff.unpack(order)

            override fun undo(order: StaffOrder) = staff.undo(order)

            override fun print(order: StaffOrder) = Unit

            override fun call(phone: String) = onOpenApp(order)

            override fun route(order: StaffOrder) = onOpenApp(order)
        }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (order?.scheduledFor != null) stringResource(R.string.alarm_heading_preorder) else stringResource(R.string.alarm_heading),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            if (waiting.size > 1) {
                Text(pluralStringResource(R.plurals.alarm_more, waiting.size - 1, waiting.size - 1), style = MaterialTheme.typography.titleMedium)
            }
            state.alarm.silencedUntil?.let { Text(stringResource(R.string.silenced_until, Format.clock(it))) }
            for (card in shown) {
                OrderCard(
                    order = card,
                    now = state.now,
                    prep = state.queue.prep,
                    prefs = state.prefs,
                    pending = state.pending[card.id],
                    busy = card.id in state.busy,
                    canPrint = false,
                    actions = actions,
                    busyMinutes = state.busyMinutes,
                    compact = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.alarm.ringing.isNotEmpty()) {
                    Button(
                        onClick = staff::silence,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.alarm_silence_until, Format.clock(state.now.plusSeconds(state.prefs.alarm.silenceSeconds.toLong()))))
                    }
                }
                OutlinedButton(onClick = { onOpenApp(order) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.alarm_open_queue))
                }
            }
            Text(stringResource(R.string.alarm_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
