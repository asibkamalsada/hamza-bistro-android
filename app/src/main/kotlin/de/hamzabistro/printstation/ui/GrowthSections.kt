package de.hamzabistro.printstation.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Growth
import de.hamzabistro.printstation.core.GrowthMetric
import de.hamzabistro.printstation.core.GrowthText
import de.hamzabistro.printstation.core.GrowthWords
import de.hamzabistro.printstation.core.ReportText
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/* The Auswertung's Fortschritt, Kunden and Monat für Monat, from staff_growth(). */

/** The headings of the months' CSV, from the resources. */
internal fun growthWords(context: Context): GrowthWords =
    GrowthWords(
        month = context.getString(R.string.report_months_month),
        orders = context.getString(R.string.report_orders),
        revenue = context.getString(R.string.report_revenue),
        webOrders = context.getString(R.string.report_months_web_orders),
        newCustomers = context.getString(R.string.report_growth_new_customers),
        newShare = context.getString(R.string.report_months_new_share),
        newAccounts = context.getString(R.string.report_growth_new_accounts),
        registered = context.getString(R.string.report_months_registered),
    )

/** The range beside the one before it: each figure, what it was, and the change. */
@Composable
internal fun GrowthProgress(growth: Growth) {
    val now = growth.current
    val before = growth.previous
    ReportSection(stringResource(R.string.report_growth)) {
        Text(
            stringResource(R.string.report_growth_against, rangeText(before.range)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Compared(stringResource(R.string.report_revenue), ReportText.euro(now.revenue), ReportText.euro(before.revenue), GrowthText.change(now.revenue, before.revenue))
        Compared(stringResource(R.string.report_orders), now.orders.toString(), before.orders.toString(), GrowthText.count(now.orders, before.orders))
        Compared(
            stringResource(R.string.report_basket),
            ReportText.euro(now.averageBasket),
            ReportText.euro(before.averageBasket),
            GrowthText.change(now.averageBasket, before.averageBasket),
        )
        Compared(
            stringResource(R.string.report_growth_new_customers),
            now.newCustomers.toString(),
            before.newCustomers.toString(),
            GrowthText.count(now.newCustomers, before.newCustomers),
        )
        Compared(
            stringResource(R.string.report_growth_new_share),
            ReportText.percent(now.newCustomerShare),
            ReportText.percent(before.newCustomerShare),
            GrowthText.points(now.newCustomerShare, before.newCustomerShare, stringResource(R.string.report_growth_points)),
        )
        Compared(
            stringResource(R.string.report_growth_new_accounts),
            now.newAccounts.toString(),
            before.newAccounts.toString(),
            GrowthText.count(now.newAccounts, before.newAccounts),
        )
    }
}

/** Two lines, not columns: beside three numbers a phone leaves the names no room. */
@Composable
private fun Compared(label: String, now: String, before: String, change: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(now, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.report_growth_before, before),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (change != null) {
            val color =
                when (GrowthText.direction(change)) {
                    1 -> MaterialTheme.colorScheme.primary
                    -1 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            Text(change, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
        }
    }
}

/** New and returning in the range; registered, ever ordered and the last 30 days as of today. */
@Composable
internal fun GrowthCustomersSection(growth: Growth) {
    val span = growth.current
    val customers = growth.customers
    ReportSection(stringResource(R.string.report_customers)) {
        Subheading(stringResource(R.string.report_customers_range))
        Line(stringResource(R.string.report_customers_web_orders), span.webOrders.toString())
        Line(stringResource(R.string.report_growth_new_customers), span.newCustomers.toString(), bold = true)
        Line(stringResource(R.string.report_growth_new_share), ReportText.percent(span.newCustomerShare), bold = true)
        Line(stringResource(R.string.report_customers_returning), (span.webOrders - span.newCustomers).toString(), muted = true)
        Line(stringResource(R.string.report_customers_new_revenue), ReportText.euro(span.newCustomerRevenue))
        Line(stringResource(R.string.report_growth_new_accounts), span.newAccounts.toString())
        Line(stringResource(R.string.report_customers_registered_at, dateText(span.range.to)), customers.registered.toString())
        Spacer(Modifier.height(8.dp))
        Subheading(stringResource(R.string.report_customers_today))
        Line(stringResource(R.string.report_customers_registered_now), customers.registeredNow.toString(), bold = true)
        Line(stringResource(R.string.report_customers_ordered_ever, ReportText.percent(customers.orderedShare)), customers.orderedEver.toString())
        Line(stringResource(R.string.report_customers_active), customers.active30Days.toString())
        Line(stringResource(R.string.report_customers_repeat, ReportText.percent(customers.repeatShare)), customers.repeat30Days.toString(), muted = true)
        Text(stringResource(R.string.report_customers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Subheading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** The months as bars, by whichever figure is picked. */
@Composable
internal fun GrowthMonths(growth: Growth) {
    var metric by rememberSaveable { mutableStateOf(GrowthMetric.REVENUE) }
    val month = DateTimeFormatter.ofPattern("MMM yyyy", LocalConfiguration.current.locales[0])
    val most = growth.months.maxOfOrNull { metric.of(it) } ?: 0.0
    ReportSection(stringResource(R.string.report_months)) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((choice, label) in METRICS) {
                FilterChip(selected = metric == choice, onClick = { metric = choice }, label = { Text(stringResource(label)) })
            }
        }
        for (row in growth.months) {
            Line(month.format(row.date), metric.text(row))
            if (most > 0) Bar(metric.of(row) / most)
        }
        val until = growth.current.range.to
        if (until != until.with(TemporalAdjusters.lastDayOfMonth())) {
            Text(
                stringResource(R.string.report_months_until, dateText(until)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val METRICS =
    listOf(
        GrowthMetric.REVENUE to R.string.report_revenue,
        GrowthMetric.ORDERS to R.string.report_months_orders,
        GrowthMetric.NEW_CUSTOMERS to R.string.report_months_new,
        GrowthMetric.REGISTERED to R.string.report_months_registered,
    )
