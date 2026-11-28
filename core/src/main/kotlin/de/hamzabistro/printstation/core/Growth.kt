package de.hamzabistro.printstation.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * What staff_growth(p_from, p_to) answers (20261128180000_growth_report.sql in
 * hamza-bistro-web): how the shop progresses. The chosen span beside the one
 * before it, the registered customers, and the months. Counts and sums only.
 *
 * A "new customer" is a website order whose email had no delivery on record
 * when it was placed; the server clears emails after 30 days, so it reads "no
 * delivery in the 30 days before". "Registered" is a confirmed customer
 * account, staff and print stations not among them.
 */

/** One span's figures: sales are delivered orders, neither tests nor stornoed. */
@Serializable
data class GrowthSpan(
    val from: String,
    val to: String,
    val orders: Int = 0,
    val revenue: Double = 0.0,
    @SerialName("average_basket") val averageBasket: Double? = null,
    @SerialName("web_orders") val webOrders: Int = 0,
    /** Of [webOrders]. */
    @SerialName("new_customers") val newCustomers: Int = 0,
    /** [newCustomers] over [webOrders], 0 to 1; null with no website order. */
    @SerialName("new_customer_share") val newCustomerShare: Double? = null,
    @SerialName("new_customer_revenue") val newCustomerRevenue: Double = 0.0,
    /** Accounts made in the span. */
    @SerialName("new_accounts") val newAccounts: Int = 0,
) {
    val range: ReportRange
        get() = ReportRange(LocalDate.parse(from), LocalDate.parse(to))
}

@Serializable
data class GrowthCustomers(
    /** Registered at the end of the chosen span. */
    val registered: Int = 0,
    /** Registered today, whatever the span; so are the three below. */
    @SerialName("registered_now") val registeredNow: Int = 0,
    /** Of [registeredNow], who has ever ordered online. */
    @SerialName("ordered_ever") val orderedEver: Int = 0,
    /** Who had a website order delivered in the last 30 days. */
    @SerialName("active_30_days") val active30Days: Int = 0,
    /** Of [active30Days], who more than once. */
    @SerialName("repeat_30_days") val repeat30Days: Int = 0,
) {
    val orderedShare: Double?
        get() = share(orderedEver, registeredNow)

    val repeatShare: Double?
        get() = share(repeat30Days, active30Days)

    private fun share(part: Int, whole: Int): Double? = if (whole == 0) null else part.toDouble() / whole
}

/** One calendar month, the last one only up to the span's end. */
@Serializable
data class GrowthMonth(
    /** Its first day. */
    val month: String,
    val orders: Int = 0,
    val revenue: Double = 0.0,
    @SerialName("web_orders") val webOrders: Int = 0,
    @SerialName("new_customers") val newCustomers: Int = 0,
    @SerialName("new_accounts") val newAccounts: Int = 0,
    /** Registered at its end. */
    @SerialName("accounts_total") val accountsTotal: Int = 0,
) {
    val date: LocalDate
        get() = LocalDate.parse(month)

    val newCustomerShare: Double?
        get() = if (webOrders == 0) null else newCustomers.toDouble() / webOrders
}

/** The whole of staff_growth() for one span. */
@Serializable
data class Growth(
    val current: GrowthSpan,
    /** The same days a month earlier for a month, else as many whole weeks earlier as the span is long. */
    val previous: GrowthSpan,
    val customers: GrowthCustomers = GrowthCustomers(),
    /** At most twelve, oldest first, none before the first sale or sign-up. */
    val months: List<GrowthMonth> = emptyList(),
)

/** What the months can be drawn by. */
enum class GrowthMetric {
    REVENUE,
    ORDERS,
    NEW_CUSTOMERS,
    REGISTERED;

    fun of(month: GrowthMonth): Double =
        when (this) {
            REVENUE -> month.revenue
            ORDERS -> month.orders.toDouble()
            NEW_CUSTOMERS -> month.newCustomers.toDouble()
            REGISTERED -> month.accountsTotal.toDouble()
        }

    /** "1234,00 €" or "42". */
    fun text(month: GrowthMonth): String =
        if (this == REVENUE) ReportText.euro(month.revenue) else of(month).toInt().toString()
}

/** How a change against the span before reads. */
object GrowthText {
    /** "+12,5 %", "−3 %", "±0 %"; null when there was nothing before to grow from. */
    fun change(now: Double?, before: Double?): String? =
        if (now == null || before == null || before == 0.0) null else signed((now - before) / before * 100, "%")

    /** "+3 · +25 %"; "+3" from nothing. */
    fun count(now: Int, before: Int): String =
        listOfNotNull(signed((now - before).toDouble(), null), change(now.toDouble(), before.toDouble())).joinToString(" · ")

    /** Between two shares, in percentage points: "+5 Pkt."; null without both. */
    fun points(now: Double?, before: Double?, unit: String): String? =
        if (now == null || before == null) null else signed((now - before) * 100, unit)

    /** Up, down or neither, as [signed] rounds it: 1, -1 or 0. */
    fun direction(change: String?): Int =
        when (change?.firstOrNull()) {
            '+' -> 1
            MINUS -> -1
            else -> 0
        }

    private fun signed(value: Double, unit: String?): String {
        val number = ReportText.tenth(abs(value))
        val sign =
            when {
                number == "0" -> "±"
                value > 0 -> "+"
                else -> MINUS.toString()
            }
        return if (unit == null) "$sign$number" else "$sign$number $unit"
    }

    /** A real minus sign, as wide as the plus. */
    const val MINUS = '−'
}

/** The headings of the months' CSV, from the app's resources. */
data class GrowthWords(
    val month: String,
    val orders: String,
    val revenue: String,
    val webOrders: String,
    val newCustomers: String,
    val newShare: String,
    val newAccounts: String,
    val registered: String,
)

/** The months as a fourth CSV file beside [ReportCsv]'s, written the same way: for a chart in a spreadsheet. */
object GrowthCsv {
    /** "auswertung_2025-03-29_2025-03-31_monate.csv"; the share of new customers in percent. */
    fun months(growth: Growth, words: GrowthWords): ReportCsv.File =
        ReportCsv.File(
            "auswertung_${growth.current.from}_${growth.current.to}_monate.csv",
            ReportCsv.table(
                listOf(
                    words.month,
                    words.orders,
                    words.revenue,
                    words.webOrders,
                    words.newCustomers,
                    words.newShare,
                    words.newAccounts,
                    words.registered,
                ),
                growth.months.map { month ->
                    listOf(
                        DATE.format(month.date),
                        month.orders.toString(),
                        ReportCsv.money(month.revenue),
                        month.webOrders.toString(),
                        month.newCustomers.toString(),
                        month.newCustomerShare?.let { ReportText.tenth(it * 100) } ?: "",
                        month.newAccounts.toString(),
                        month.accountsTotal.toString(),
                    )
                },
            ),
        )

    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
}
