package de.hamzabistro.printstation.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * What staff_report(p_from, p_to) answers (20261003130000_staff_report.sql in
 * hamza-bistro-web): aggregates over a range of Leipzig days, no personal
 * data. Money in euros to the cent, durations in minutes to a tenth, rates
 * from 0 to 1. An average, median or rate over nothing is null ("–"); counts
 * and sums are 0. Postgres sends object keys in its own order, so everything
 * is read by name; arrays keep the server's order.
 */

@Serializable
data class ReportTotals(
    val placed: Int = 0,
    val delivered: Int = 0,
    val cancelled: Int = 0,
    val open: Int = 0,
    val revenue: Double = 0.0,
    @SerialName("average_basket") val averageBasket: Double? = null,
    @SerialName("delivery_fees") val deliveryFees: Double = 0.0,
    @SerialName("small_order_fees") val smallOrderFees: Double = 0.0,
    val deposits: Double = 0.0,
)

/** One Leipzig day: delivered orders on the day they were delivered, cancelled ones on the day they were placed. */
@Serializable
data class ReportDay(
    val day: String,
    val delivered: Int = 0,
    val revenue: Double = 0.0,
    @SerialName("average_basket") val averageBasket: Double? = null,
    val cancelled: Int = 0,
) {
    val date: LocalDate
        get() = LocalDate.parse(day)
}

@Serializable
data class ReportShare(
    val orders: Int = 0,
    val revenue: Double = 0.0,
    @SerialName("average_basket") val averageBasket: Double? = null,
)

@Serializable
data class ReportFulfilment(val delivery: ReportShare = ReportShare(), val pickup: ReportShare = ReportShare())

/** One delivery ring: inner, near, far, edge, always in that order. */
@Serializable
data class ReportRing(
    val ring: String,
    val orders: Int = 0,
    val revenue: Double = 0.0,
    @SerialName("delivery_fees") val deliveryFees: Double = 0.0,
    @SerialName("small_order_fees") val smallOrderFees: Double = 0.0,
)

/** A dish sold; [revenue] is what its lines charged, before order-wide discounts. */
@Serializable
data class ReportDish(
    /** Null for very old lines. */
    @SerialName("item_id") val itemId: Long? = null,
    val name: String = "",
    val qty: Int = 0,
    val revenue: Double = 0.0,
)

/** One weekday × hour cell with orders in it; a pre-order counts in the hour of its slot. */
@Serializable
data class ReportHour(
    /** 1 = Monday … 7 = Sunday. */
    val weekday: Int,
    val hour: Int,
    val orders: Int = 0,
    val revenue: Double = 0.0,
)

@Serializable
data class ReportCancellation(
    /** "customer", "staff" or "system". */
    @SerialName("cancelled_by") val cancelledBy: String? = null,
    /** "busy", "sold_out", "unreachable", "address", "timeout", or null for none given. */
    val reason: String? = null,
    val orders: Int = 0,
    val value: Double = 0.0,
)

@Serializable
data class ReportCancellations(
    val orders: Int = 0,
    val value: Double = 0.0,
    val by: List<ReportCancellation> = emptyList(),
)

@Serializable
data class ReportDuration(val orders: Int = 0, val median: Double? = null, val p90: Double? = null)

@Serializable
data class ReportTimes(
    @SerialName("new_to_accepted") val newToAccepted: ReportDuration = ReportDuration(),
    @SerialName("accepted_to_out") val acceptedToOut: ReportDuration = ReportDuration(),
    @SerialName("out_to_delivered") val outToDelivered: ReportDuration = ReportDuration(),
    @SerialName("accepted_to_delivered") val acceptedToDelivered: ReportDuration = ReportDuration(),
    @SerialName("accepted_to_collected") val acceptedToCollected: ReportDuration = ReportDuration(),
)

@Serializable
data class ReportLateness(
    val orders: Int = 0,
    val late: Int = 0,
    val rate: Double? = null,
    @SerialName("median_minutes_late") val medianMinutesLate: Double? = null,
    @SerialName("p90_minutes_late") val p90MinutesLate: Double? = null,
)

@Serializable
data class ReportLate(val delivery: ReportLateness = ReportLateness(), val pickup: ReportLateness = ReportLateness())

@Serializable
data class ReportCode(val code: String = "", val orders: Int = 0, val amount: Double = 0.0)

@Serializable
data class ReportCodes(
    val orders: Int = 0,
    val amount: Double = 0.0,
    @SerialName("free_delivery_orders") val freeDeliveryOrders: Int = 0,
    @SerialName("by_code") val byCode: List<ReportCode> = emptyList(),
)

@Serializable
data class ReportStamps(
    val orders: Int = 0,
    @SerialName("stamps_spent") val stampsSpent: Int = 0,
    val amount: Double = 0.0,
)

/** Deal cost is only recorded from 20261003130000 on: [ordersMeasured] says how many orders that covers. */
@Serializable
data class ReportDeals(
    val orders: Int = 0,
    val amount: Double = 0.0,
    @SerialName("orders_measured") val ordersMeasured: Int = 0,
)

@Serializable
data class ReportAmount(val orders: Int = 0, val amount: Double = 0.0)

@Serializable
data class ReportDiscounts(
    val total: Double = 0.0,
    val codes: ReportCodes = ReportCodes(),
    val stamps: ReportStamps = ReportStamps(),
    val deals: ReportDeals = ReportDeals(),
    val pickup: ReportAmount = ReportAmount(),
)

@Serializable
data class ReportPayments(
    val cash: ReportAmount = ReportAmount(),
    val card: ReportAmount = ReportAmount(),
    val online: ReportAmount = ReportAmount(),
    /** Delivered without saying Bar or Karte: Telegram, an older app. */
    val unknown: ReportAmount = ReportAmount(),
)

/** The whole of staff_report() for one range. */
@Serializable
data class Report(
    val from: String,
    val to: String,
    val days: Int = 0,
    val totals: ReportTotals = ReportTotals(),
    @SerialName("by_day") val byDay: List<ReportDay> = emptyList(),
    val fulfilment: ReportFulfilment = ReportFulfilment(),
    val rings: List<ReportRing> = emptyList(),
    @SerialName("best_sellers") val bestSellers: List<ReportDish> = emptyList(),
    val hours: List<ReportHour> = emptyList(),
    val cancellations: ReportCancellations = ReportCancellations(),
    val times: ReportTimes = ReportTimes(),
    val late: ReportLate = ReportLate(),
    val discounts: ReportDiscounts = ReportDiscounts(),
    val payments: ReportPayments = ReportPayments(),
) {
    val range: ReportRange
        get() = ReportRange(LocalDate.parse(from), LocalDate.parse(to))

    /** The big "Verspätet" number: deliveries only. */
    val lateRate: Double?
        get() = late.delivery.rate

    /** The best sellers by units (as sent) or by what they made, most first. */
    fun bestSellers(by: DishOrder): List<ReportDish> =
        when (by) {
            DishOrder.QTY -> bestSellers.sortedWith(compareByDescending<ReportDish> { it.qty }.thenByDescending { it.revenue })
            DishOrder.REVENUE -> bestSellers.sortedWith(compareByDescending<ReportDish> { it.revenue }.thenByDescending { it.qty })
        }

    /** The weekday × hour grid with every empty cell 0. */
    val grid: BusyGrid
        get() = BusyGrid.of(hours)

    companion object {
        /** How many best sellers show before "alle anzeigen". */
        const val TOP = 10
    }
}

enum class DishOrder {
    /** "Menge". */
    QTY,

    /** "Umsatz". */
    REVENUE,
}

/** From one Leipzig day to another, both included. */
data class ReportRange(val from: LocalDate, val to: LocalDate) {
    val days: Long
        get() = ChronoUnit.DAYS.between(from, to) + 1

    companion object {
        /** The most staff_report() covers (HB455 beyond). */
        const val MAX_DAYS = 366L

        /**
         * A chosen range made one the server takes: the ends in order, not
         * past [today], and at most [MAX_DAYS] long, keeping the end and
         * moving the start up.
         */
        fun clamp(from: LocalDate, to: LocalDate, today: LocalDate): ReportRange {
            var start = minOf(from, to)
            val end = minOf(maxOf(from, to), today)
            start = minOf(start, end)
            val earliest = end.minusDays(MAX_DAYS - 1)
            if (start < earliest) start = earliest
            return ReportRange(start, end)
        }
    }
}

/** The quick choices above the report; [CUSTOM] is a range from the calendar. */
enum class ReportPreset {
    THIS_WEEK,
    LAST_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    CUSTOM;

    /** The range on a Leipzig calendar, [today] being today there; a week starts on Monday. Null for [CUSTOM]. */
    fun range(today: LocalDate): ReportRange? {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val first = today.withDayOfMonth(1)
        return when (this) {
            THIS_WEEK -> ReportRange(monday, today)
            LAST_WEEK -> ReportRange(monday.minusWeeks(1), monday.minusDays(1))
            THIS_MONTH -> ReportRange(first, today)
            LAST_MONTH -> ReportRange(first.minusMonths(1), first.minusDays(1))
            CUSTOM -> null
        }
    }

    companion object {
        /** Which quick choice [range] is, if any, so a chosen range that happens to be "Letzte Woche" reads as it. */
        fun of(range: ReportRange, today: LocalDate): ReportPreset =
            entries.firstOrNull { it != CUSTOM && it.range(today) == range } ?: CUSTOM
    }
}

/** Orders per weekday × hour, 7 × 24, every cell there. */
class BusyGrid private constructor(private val orders: Array<IntArray>, private val revenue: Array<DoubleArray>) {
    /** Orders on [weekday] (1 = Monday … 7 = Sunday) in [hour] (0–23). */
    fun orders(weekday: Int, hour: Int): Int = orders[weekday - 1][hour]

    fun revenue(weekday: Int, hour: Int): Double = revenue[weekday - 1][hour]

    /** The busiest cell's orders: what full shade means. 0 for an empty grid. */
    val max: Int = orders.maxOf { row -> row.max() }

    /** 0 for no orders up to 1 for the busiest cell, for the shade. */
    fun shade(weekday: Int, hour: Int): Double = if (max == 0) 0.0 else orders(weekday, hour).toDouble() / max

    /**
     * The hours worth a column on a phone: from the first with any orders
     * to the last, gaps inside kept so the hours read in a row. Empty for
     * an empty grid.
     */
    val busyHours: IntRange
        get() {
            val any = (0 until HOURS).filter { hour -> (1..WEEKDAYS).any { orders(it, hour) > 0 } }
            return if (any.isEmpty()) IntRange.EMPTY else any.first()..any.last()
        }

    companion object {
        const val WEEKDAYS = 7
        const val HOURS = 24

        /** The grid from the cells with orders; anything off the grid is left out. */
        fun of(cells: List<ReportHour>): BusyGrid {
            val orders = Array(WEEKDAYS) { IntArray(HOURS) }
            val revenue = Array(WEEKDAYS) { DoubleArray(HOURS) }
            for (cell in cells) {
                if (cell.weekday !in 1..WEEKDAYS || cell.hour !in 0 until HOURS) continue
                orders[cell.weekday - 1][cell.hour] += cell.orders
                revenue[cell.weekday - 1][cell.hour] += cell.revenue
            }
            return BusyGrid(orders, revenue)
        }
    }
}

/**
 * The words the report is said in, from the app's resources: the core puts
 * them together, the same way on the screen, in the text shared and in the
 * CSV.
 */
data class ReportWords(
    /** The first line of the shared text: "Auswertung 01.09.2026 – 30.09.2026". */
    val title: String,
    val revenue: String,
    val orders: String,
    val basket: String,
    val late: String,
    val day: String,
    val delivered: String,
    val cancelled: String,
    val delivery: String,
    val pickup: String,
    val ring: String,
    val ringInner: String,
    val ringNear: String,
    val ringFar: String,
    val ringEdge: String,
    val deliveryFees: String,
    val smallOrderFees: String,
    val dish: String,
    val qty: String,
    val bestSellers: String,
    val cash: String,
    val card: String,
    val online: String,
    val unknown: String,
    val byCustomer: String,
    val byStaff: String,
    val bySystem: String,
    val reasonBusy: String,
    val reasonSoldOut: String,
    val reasonUnreachable: String,
    val reasonAddress: String,
    /** "automatisch abgelehnt", the auto-decline's own. */
    val reasonTimeout: String,
    val reasonNone: String,
    /** "{n} Min.". */
    val minutes: String,
    /** "Im Zeitraum wurde nichts geliefert." */
    val empty: String,
)

/** How the report reads: numbers in German whatever the device's language, as on the ticket and in the Kassensturz. */
object ReportText {
    /** "–" for nothing to say. */
    const val NONE = "–"

    fun euro(amount: Double?): String = amount?.let { CashUpText.euro(it) } ?: NONE

    /** "66,7 %"; "–" for null. */
    fun percent(rate: Double?): String = rate?.let { "${tenth(it * 100)} %" } ?: NONE

    /** "37,5 Min.", "3 Min."; "–" for null. */
    fun minutes(minutes: Double?, words: ReportWords): String = minutes?.let { words.minutes.replace("{n}", tenth(it)) } ?: NONE

    /** "37,5", "3": to a tenth, German comma, no ",0". */
    fun tenth(value: Double): String = DecimalFormat("0.#", DecimalFormatSymbols(Locale.GERMANY)).format(value)

    fun ring(ring: String, words: ReportWords): String =
        when (ring) {
            "inner" -> words.ringInner
            "near" -> words.ringNear
            "far" -> words.ringFar
            "edge" -> words.ringEdge
            else -> ring
        }

    /**
     * Who cancelled and why: "Kunde", "Personal · Zu viel los",
     * "automatisch abgelehnt" for the auto-decline. Anything the app does not
     * know yet reads as the database spells it.
     */
    fun cancellation(row: ReportCancellation, words: ReportWords): String {
        if (row.reason == "timeout") return words.reasonTimeout
        val who =
            when (row.cancelledBy) {
                "customer" -> words.byCustomer
                "staff" -> words.byStaff
                "system" -> words.bySystem
                null -> null
                else -> row.cancelledBy
            }
        val why =
            when (row.reason) {
                "busy" -> words.reasonBusy
                "sold_out" -> words.reasonSoldOut
                "unreachable" -> words.reasonUnreachable
                "address" -> words.reasonAddress
                null -> if (row.cancelledBy == "customer") null else words.reasonNone
                else -> row.reason
            }
        return listOfNotNull(who, why).joinToString(" · ").ifEmpty { words.reasonNone }
    }

    /** "6× Probe Ayran · 15,02 €". */
    fun dish(dish: ReportDish): String = "${dish.qty}× ${dish.name} · ${euro(dish.revenue)}"

    /** "Bar 21,01 € · Karte 30,00 € · Online 15,00 € · unbekannt 9,00 €": Bar and Karte always, the others when there are any. */
    fun payments(payments: ReportPayments, words: ReportWords): String =
        buildList {
                add("${words.cash} ${euro(payments.cash.amount)}")
                add("${words.card} ${euro(payments.card.amount)}")
                if (payments.online.orders > 0) add("${words.online} ${euro(payments.online.amount)}")
                if (payments.unknown.orders > 0) add("${words.unknown} ${euro(payments.unknown.amount)}")
            }
            .joinToString(" · ")

    /**
     * The summary as plain text, to share: the big numbers, delivery and
     * pickup, payments, cancellations and the top best sellers. Counts and
     * money only — staff_report() sends nothing about the customers.
     */
    fun share(report: Report, words: ReportWords): String =
        buildString {
            appendLine(words.title)
            val totals = report.totals
            appendLine("${words.revenue} ${euro(totals.revenue)}")
            appendLine("${words.orders} ${totals.delivered}")
            appendLine("${words.basket} ${euro(totals.averageBasket)}")
            append("${words.late} ${percent(report.lateRate)}")
            if (totals.delivered == 0) {
                appendLine()
                appendLine()
                append(words.empty)
                return@buildString
            }
            appendLine()
            appendLine()
            val f = report.fulfilment
            appendLine("${words.delivery} ${f.delivery.orders} · ${euro(f.delivery.revenue)}")
            appendLine("${words.pickup} ${f.pickup.orders} · ${euro(f.pickup.revenue)}")
            append(payments(report.payments, words))
            if (report.cancellations.orders > 0) {
                appendLine()
                append("${words.cancelled} ${report.cancellations.orders} · ${euro(report.cancellations.value)}")
            }
            val top = report.bestSellers(DishOrder.QTY).take(Report.TOP)
            if (top.isNotEmpty()) {
                appendLine()
                appendLine()
                append("${words.bestSellers}:")
                for (dish in top) {
                    appendLine()
                    append("  ").append(dish(dish))
                }
            }
        }
}

/**
 * The report as CSV for a spreadsheet or the Steuerberater: one file per
 * table (by_day, best_sellers, rings), as German Excel opens them by a
 * double tap — ";" between cells, a decimal comma, no thousands separator
 * and no "€", dates as 29.03.2025, UTF-8 with a byte order mark so "Döner"
 * stays "Döner". An empty cell for nothing to say (an average over nothing).
 */
object ReportCsv {
    data class File(val name: String, val text: String)

    /** All three, named after the range: "auswertung_2025-03-29_2025-03-31_tage.csv" and so on. */
    fun files(report: Report, words: ReportWords): List<File> {
        val stem = "auswertung_${report.from}_${report.to}"
        return listOf(
            File("${stem}_tage.csv", byDay(report, words)),
            File("${stem}_bestseller.csv", bestSellers(report, words)),
            File("${stem}_ringe.csv", rings(report, words)),
        )
    }

    fun byDay(report: Report, words: ReportWords): String =
        table(
            listOf(words.day, words.delivered, words.revenue, words.basket, words.cancelled),
            report.byDay.map { listOf(DATE.format(it.date), it.delivered.toString(), money(it.revenue), money(it.averageBasket), it.cancelled.toString()) },
        )

    /** Every dish sold, most units first, as the server sends them. */
    fun bestSellers(report: Report, words: ReportWords): String =
        table(
            listOf(words.dish, words.qty, words.revenue),
            report.bestSellers.map { listOf(it.name, it.qty.toString(), money(it.revenue)) },
        )

    fun rings(report: Report, words: ReportWords): String =
        table(
            listOf(words.ring, words.orders, words.revenue, words.deliveryFees, words.smallOrderFees),
            report.rings.map {
                listOf(ReportText.ring(it.ring, words), it.orders.toString(), money(it.revenue), money(it.deliveryFees), money(it.smallOrderFees))
            },
        )

    /** "21,01"; empty for null. */
    fun money(amount: Double?): String = amount?.let { DecimalFormat("0.00", DecimalFormatSymbols(Locale.GERMANY)).format(it) } ?: ""

    /** A cell quoted when it holds ";", a quote or a line break, quotes doubled. */
    fun cell(value: String): String =
        if (value.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun table(header: List<String>, rows: List<List<String>>): String =
        buildString {
            append(BOM)
            for (row in listOf(header) + rows) {
                append(row.joinToString(SEPARATOR.toString()) { cell(it) })
                append("\r\n")
            }
        }

    const val SEPARATOR = ';'
    const val BOM = "﻿"
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
}
