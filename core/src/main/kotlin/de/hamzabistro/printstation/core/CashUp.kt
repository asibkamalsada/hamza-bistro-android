package de.hamzabistro.printstation.core

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a delivered order was paid (20261002190000_cash_up.sql in
 * hamza-bistro-web): cash or card at the door or the counter, said with the
 * "Bar" / "Karte" buttons; online from the moment its money is held.
 */
@Serializable
enum class PaymentMethod {
    @SerialName("cash") CASH,
    @SerialName("card") CARD,

    /** The payment system's to say, never staff's: the database refuses it from them (HB438). */
    @SerialName("online") ONLINE;

    /** As the column spells it. */
    val wire: String
        get() =
            when (this) {
                CASH -> "cash"
                CARD -> "card"
                ONLINE -> "online"
            }

    companion object {
        /** What staff can say at the door: "Bar", then "Karte". */
        val DOOR: List<PaymentMethod> = listOf(CASH, CARD)
    }
}

/** The last step and how it was paid — asksPayment() and deliver() of the site's /orders. */
object Payment {
    /**
     * Whether the last step asks how it was paid: everything on its way is
     * paid at the door or the counter, except an order whose money is held
     * online, which keeps the one "Geliefert" button: that tap takes it.
     */
    fun asks(order: StaffOrder): Boolean =
        order.status == OrderStatus.ON_THE_WAY && order.paymentMethod != PaymentMethod.ONLINE

    /**
     * The step for "Bar" or "Karte" — or for the one button, with
     * [payment] null. Never "online" from here, and nothing for an order
     * paid online: the database keeps that by itself. [device] is this
     * device's label, as staff_app_seen has it. [cardTerminal] is the S-POS
     * phone "Karte" went through, kept only with "Karte".
     */
    fun done(order: StaffOrder, payment: PaymentMethod?, device: String?, cardTerminal: CardTerminal? = null): OrderStep.Done {
        val paid = payment?.takeIf { asks(order) && it != PaymentMethod.ONLINE }
        return OrderStep.Done(
            payment = paid,
            device = device?.trim()?.takeIf { it.isNotEmpty() }?.take(DEVICE_MAX),
            cardTerminal = cardTerminal?.id?.takeIf { paid == PaymentMethod.CARD },
        )
    }

    /**
     * Whether an open order's card says that nothing is to be collected, or
     * that it is after all (hamza-bistro-web#82) — the two lines of the
     * site's orders-page.html. Null for an order paid at the door as ever,
     * and for one that is over.
     */
    fun online(order: StaffOrder): OnlineNotice? =
        when {
            !order.status.open -> null
            order.paymentMethod == PaymentMethod.ONLINE -> OnlineNotice.HELD
            order.onlineLostAt != null -> OnlineNotice.LOST
            else -> null
        }

    /**
     * Whether [step] only goes through while [order] is still paid online:
     * the last step without a method. Once the hold is lost it would record
     * an order nobody collected the money for.
     */
    fun needsHold(order: StaffOrder, step: OrderStep): Boolean =
        step is OrderStep.Done && order.paymentMethod == PaymentMethod.ONLINE

    /** What delivered_device keeps; the database cuts the rest off too. */
    const val DEVICE_MAX = 40
}

/** What an open order's card says about its online payment. */
enum class OnlineNotice {
    /** Stripe holds the money until the handover: nothing to collect. Not paid yet. */
    HELD,

    /** The hold was lost: to be collected at the door as usual. */
    LOST,
}

/** One order behind a Kassensturz sum: its number and money, nothing personal. */
@Serializable
data class CashUpOrder(
    val id: String,
    @SerialName("order_number") val orderNumber: Long,
    /** Null for an order delivered before delivered_at existed. */
    @SerialName("delivered_at") @Serializable(with = InstantSerializer::class) val deliveredAt: Instant? = null,
    val total: Double = 0.0,
    /** Null: delivered from Telegram, or by an older app — "unbekannt". */
    @SerialName("payment_method") val paymentMethod: PaymentMethod? = null,
    /** The S-POS phone of a card payment that named one (20261128100000). */
    @SerialName("card_terminal_id") val cardTerminalId: Int? = null,
    val pickup: Boolean = false,
)

/** The sums the Kassensturz gives, for the day and for each driver. In euros, to the cent. */
interface CashUpSums {
    val count: Int
    val cash: Double
    val card: Double
    val online: Double
    val unknown: Double
    val total: Double
}

@Serializable
data class CashUpTotals(
    override val count: Int = 0,
    override val cash: Double = 0.0,
    override val card: Double = 0.0,
    override val online: Double = 0.0,
    override val unknown: Double = 0.0,
    override val total: Double = 0.0,
) : CashUpSums

/** One driver: one account and one device label, or a name from Telegram. */
@Serializable
data class CashUpDriver(
    /** The device label, else the email before the @, else "Telegram: Name"; null for nobody known. */
    val label: String? = null,
    override val count: Int = 0,
    override val cash: Double = 0.0,
    override val card: Double = 0.0,
    override val online: Double = 0.0,
    override val unknown: Double = 0.0,
    override val total: Double = 0.0,
    /** In the order they were delivered. */
    val orders: List<CashUpOrder> = emptyList(),
) : CashUpSums

/**
 * What cash_up() answers for one Leipzig day: the day's sums, and one row
 * per driver, most orders first.
 */
@Serializable
data class CashUp(
    /** "2026-10-02", the Leipzig day it is for. */
    val day: String,
    val totals: CashUpTotals = CashUpTotals(),
    /**
     * Card money per S-POS phone (20261128100000): every phone in use, 0 when
     * it took nothing, any other that took money, then the unknown; adding
     * up to totals.card. Empty from a server before it.
     */
    @SerialName("card_terminals") val cardTerminals: List<CashUpTerminal> = emptyList(),
    val drivers: List<CashUpDriver> = emptyList(),
) {
    val date: LocalDate
        get() = LocalDate.parse(day)

    companion object {
        /** Today on a Leipzig calendar: what the Kassensturz opens on. */
        fun today(now: Instant, zone: ZoneId = AlarmPolicy.LEIPZIG): LocalDate = now.atZone(zone).toLocalDate()
    }
}

/**
 * The words the Kassensturz is said in, from the app's resources: the core
 * puts them together, the same way on the screen and in the text shared.
 */
data class CashUpWords(
    /** The first line of the shared text: "Kassensturz Fr., 02.10.2026". */
    val title: String,
    /** "{n} geliefert · {total}". */
    val delivered: String,
    val cash: String,
    val card: String,
    val online: String,
    val unknown: String,
    /** A driver nobody is known for: "ohne Zuordnung". */
    val nobody: String,
    /** "Abholung", after an order collected at the counter. */
    val pickup: String,
    /** "An diesem Tag wurde nichts geliefert." */
    val empty: String,
    /** "Karte je S-POS": the heading of the per-phone lines. */
    val perTerminal: String = "Karte je S-POS",
    /** After a switched-off phone: "nicht mehr in Gebrauch". */
    val terminalOff: String = "nicht mehr in Gebrauch",
)

/** How the Kassensturz reads — cashUpTotals() and cashUpSplit() of the site's /orders. */
object CashUpText {
    /** "5 geliefert · 78,40 €". */
    fun totals(sums: CashUpSums, words: CashUpWords): String =
        words.delivered.replace("{n}", sums.count.toString()).replace("{total}", euro(sums.total))

    /**
     * "Bar 40,00 € · Karte 30,90 € · unbekannt 7,50 €": cash and card
     * always, so a zero is read as a zero; online and unknown only when
     * there are any.
     */
    fun split(sums: CashUpSums, words: CashUpWords): String =
        buildList {
                add("${words.cash} ${euro(sums.cash)}")
                add("${words.card} ${euro(sums.card)}")
                if (sums.online > 0) add("${words.online} ${euro(sums.online)}")
                if (sums.unknown > 0) add("${words.unknown} ${euro(sums.unknown)}")
            }
            .joinToString(" · ")

    fun driver(driver: CashUpDriver, words: CashUpWords): String = driver.label?.takeIf { it.isNotBlank() } ?: words.nobody

    /**
     * The per-phone lines, to hold against each Sparkasse payout: shown when
     * there is a list or card money with no phone — a day of nothing but
     * cash says nothing per phone.
     */
    fun terminals(cashUp: CashUp): List<CashUpTerminal> =
        cashUp.cardTerminals.takeIf { rows -> rows.any { it.id != null } || cashUp.totals.card != 0.0 } ?: emptyList()

    /** "S-POS 1 (29001234)", "S-POS 2, nicht mehr in Gebrauch", or "unbekannt". */
    fun terminal(row: CashUpTerminal, words: CashUpWords): String {
        if (row.id == null) return words.unknown
        val name = row.tid?.takeIf { it.isNotBlank() }?.let { "${row.name} ($it)" } ?: row.name.orEmpty()
        return if (row.active == false) "$name, ${words.terminalOff}" else name
    }

    /** "S-POS 1 (29001234): 20,00 €". */
    fun terminalLine(row: CashUpTerminal, words: CashUpWords): String = "${terminal(row, words)}: ${euro(row.card)}"

    /** "Bar", "Karte", "Online" or "unbekannt". */
    fun method(method: PaymentMethod?, words: CashUpWords): String =
        when (method) {
            PaymentMethod.CASH -> words.cash
            PaymentMethod.CARD -> words.card
            PaymentMethod.ONLINE -> words.online
            null -> words.unknown
        }

    /** "#41 · 18:32 · Bar · 20,00 €", on a Leipzig clock; "–" for no time known. */
    fun order(
        order: CashUpOrder,
        words: CashUpWords,
        zone: ZoneId = AlarmPolicy.LEIPZIG,
        terminals: List<CardTerminal> = emptyList(),
    ): String =
        buildList {
                add("#${order.orderNumber}")
                add(order.deliveredAt?.let { CLOCK.format(it.atZone(zone)) } ?: "–")
                add(method(order.paymentMethod, words))
                // "Karte · S-POS 1": the phone, as it is called now.
                if (order.paymentMethod == PaymentMethod.CARD) CardTerminals.name(terminals, order.cardTerminalId)?.let { add(it) }
                if (order.pickup) add(words.pickup)
                add(euro(order.total))
            }
            .joinToString(" · ")

    /**
     * The whole Kassensturz as plain text, to share: the day, its sums, then
     * each driver with the orders behind the sums. Order numbers and money
     * only — cash_up() sends nothing about the customers.
     */
    fun share(
        cashUp: CashUp,
        words: CashUpWords,
        zone: ZoneId = AlarmPolicy.LEIPZIG,
        terminals: List<CardTerminal> = emptyList(),
    ): String =
        buildString {
            appendLine(words.title)
            if (cashUp.totals.count == 0) {
                append(words.empty)
                return@buildString
            }
            appendLine(totals(cashUp.totals, words))
            append(split(cashUp.totals, words))
            val perTerminal = terminals(cashUp)
            if (perTerminal.isNotEmpty()) {
                appendLine()
                appendLine()
                append("${words.perTerminal}:")
                for (row in perTerminal) {
                    appendLine()
                    append("  ").append(terminalLine(row, words))
                }
            }
            for (driver in cashUp.drivers) {
                appendLine()
                appendLine()
                appendLine("${driver(driver, words)}: ${totals(driver, words)}")
                append(split(driver, words))
                for (order in driver.orders) {
                    appendLine()
                    append("  ").append(order(order, words, zone, terminals))
                }
            }
        }

    /** "23,40 €" — in German whatever the device's language, as on the ticket. */
    fun euro(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.GERMANY).apply { currency = Currency.getInstance("EUR") }.format(amount)

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
}
