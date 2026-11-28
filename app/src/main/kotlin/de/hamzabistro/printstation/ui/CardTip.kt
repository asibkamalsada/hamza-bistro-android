package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.CashUpText
import de.hamzabistro.printstation.core.Tips

// A tip paid on the card (hamza-bistro-web 20261128110000_card_tips): the
// customer pays more than the bill on S-POS, staff say what S-POS charged,
// and the difference is their tip, paid out in cash at the end of the shift.

/**
 * "Trinkgeld": what S-POS charged, starting at the bill [total]; [onTip]
 * gets the tip (0.0 when it is just the bill). An amount that is no tip
 * (less than the bill, more than 50 € on top) cannot be taken.
 */
@Composable
internal fun TipDialog(total: Double, onTip: (Double) -> Unit, onDismiss: () -> Unit) {
    var charged by rememberSaveable(total) { mutableStateOf(Tips.start(total)) }
    val tip = Tips.from(charged, total)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tip_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tip_bill, CashUpText.euro(total)))
                OutlinedTextField(
                    value = charged,
                    onValueChange = { charged = it.take(9) },
                    label = { Text(stringResource(R.string.tip_charged)) },
                    singleLine = true,
                    isError = tip == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        Text(
                            if (tip == null) stringResource(R.string.tip_invalid)
                            else stringResource(R.string.tip_is, CashUpText.euro(tip))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { tip?.let(onTip) }, enabled = tip != null) { Text(stringResource(R.string.tip_take)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_back)) } },
    )
}
