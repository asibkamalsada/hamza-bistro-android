package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.CardChoice
import de.hamzabistro.printstation.core.CardTerminal
import de.hamzabistro.printstation.core.CardTerminals
import de.hamzabistro.printstation.core.PaymentMethod

// "Karte" names the S-POS phone it went through (hamza-bistro-web
// 20261128100000_card_terminals): every phone is its own Sparkasse contract
// and payout, and the Kassensturz adds the card money up per phone.

/** Every phone [choice] offers, the usual one first. */
internal fun offeredTerminals(choice: CardChoice): List<CardTerminal> =
    when (choice) {
        is CardChoice.Usual -> listOf(choice.terminal) + choice.others
        is CardChoice.Ask -> choice.terminals
        CardChoice.NoList -> emptyList()
    }

/**
 * "Karte" at the door: one tap on this device's usual phone ("Karte · S-POS 2",
 * and "anderes S-POS" beside it), a pick with none, and as before with no
 * list. [onPaid] gets the phone, null for none.
 */
@Composable
internal fun CardButton(choice: CardChoice, modifier: Modifier = Modifier, enabled: Boolean = true, onPaid: (CardTerminal?) -> Unit) {
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    Button(
        onClick = {
            when (choice) {
                is CardChoice.Usual -> onPaid(choice.terminal)
                is CardChoice.Ask -> picking = true
                CardChoice.NoList -> onPaid(null)
            }
        },
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(CardTerminals.cardLabel(paymentLabel(context, PaymentMethod.CARD), choice))
    }
    if (choice is CardChoice.Usual && choice.others.isNotEmpty()) {
        TextButton(onClick = { picking = true }, enabled = enabled) { Text(stringResource(R.string.card_terminal_other)) }
    }
    if (picking) {
        CardTerminalDialog(
            offeredTerminals(choice),
            onPick = { terminal ->
                picking = false
                onPaid(terminal)
            },
            onDismiss = { picking = false },
        )
    }
}

/** "Welches S-POS?": the phones in use, one button each. */
@Composable
internal fun CardTerminalDialog(terminals: List<CardTerminal>, onPick: (CardTerminal) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_terminal_pick)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (terminal in terminals) {
                    OutlinedButton(onClick = { onPick(terminal) }, modifier = Modifier.fillMaxWidth()) { Text(terminal.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_back)) } },
    )
}
