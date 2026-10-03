package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Kitchen
import de.hamzabistro.printstation.core.KitchenSlot
import java.time.Instant

/**
 * The kitchen's next hour above the queue, as on /orders
 * (hamza-bistro-web#73): "Küche, nächste Stunde 18:00 ■■■■□ 18:15 ■■■■■ …",
 * a quarter hour that is full or over in the warn colour. So whoever
 * accepts sees what "15 Min." really means. Nothing while the cap is off or
 * on a database without it. On a phone the quarter hours wrap, each whole.
 */
@Composable
fun KitchenLoadLine(slots: List<KitchenSlot>?, now: Instant) {
    val quarter = Kitchen.slotOf(now)
    val line = remember(slots, quarter) { Kitchen.line(slots, now) }
    if (line.isEmpty()) return
    val resources = LocalResources.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.kitchen_load_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (slot in line) {
            val time = Format.clock(slot.slot)
            Text(
                "$time ${slot.bar}",
                style = MaterialTheme.typography.bodyLarge,
                color = if (slot.full) MaterialTheme.colorScheme.error else Color.Unspecified,
                fontWeight = if (slot.full) FontWeight.Bold else null,
                maxLines = 1,
                softWrap = false,
                modifier =
                    Modifier.semantics {
                        contentDescription = resources.getString(R.string.kitchen_load_slot, time, slot.dishes, slot.capacity)
                    },
            )
        }
    }
}
