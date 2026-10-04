package de.hamzabistro.printstation.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** What a device tells the shop about itself while it is on shift. */
data class DeviceReport(
    /** This device's id: random, made once and kept. */
    val id: String,
    /** "Tablet", "Fahrer 1" — how /orders/settings lists it. */
    val label: String,
    /** How it alarms about a new order: "loop", "once" or "off". */
    val alarm: String,
    /** This build's versionName, "0.2.57"; blank keeps what the site has. */
    val version: String? = null,
)

/** The queue as staff work it: read it, move an order on, say this device is listening. */
interface StaffBackend {
    /** Whether the signed-in account is staff — and binds a staff row added by email to it. */
    suspend fun isStaff(): Boolean

    /** Every order still in play: new, accepted, on its way. */
    suspend fun openOrders(): List<StaffOrder>

    /**
     * Moves [order] one [step] on — but only from where this device saw it.
     * Throws [OrderMovedException] when it had moved on meanwhile,
     * [NotDelayableException] for an [OrderStep.Delay] the database refused,
     * [PaymentLockedException] when it refused how it was paid,
     * [NotPackableException] for an [OrderStep.Pack] or [OrderStep.Unpack]
     * on an order that is no accepted delivery (any more), and
     * [PackingUnavailableException] when the database has no "Fertig" yet.
     */
    suspend fun move(order: StaffOrder, step: OrderStep)

    /**
     * "Alle +[minutes] Min.": every accepted order for right away, at once
     * (delay_open_orders). Answers how many it moved.
     */
    suspend fun delayOpen(minutes: Int): Int

    /**
     * "Zähler zurücksetzen": the "nicht angetroffen" count of the account
     * behind [order] back to 0 (reset_no_shows). Answers the count it had.
     * Throws [NoShowAccountGoneException] when retention has already cleared
     * who placed it, [NotAllowedException] for an account that is not staff,
     * and [NeedsServerUpdateException] on a database without no-shows.
     */
    suspend fun resetNoShows(order: StaffOrder): Int

    /** Minutes per dish, for the suggested ETA. */
    suspend fun prepMinutes(): Map<Long, Int>

    /**
     * Says this device is on shift and will ring, so "Wer von Bestellungen
     * erfährt" on /orders/settings lists it. Throws [NotAllowedException]
     * when the account is no longer staff.
     */
    suspend fun seen(device: DeviceReport)

    /** Takes this device off that list: its shift ended here. */
    suspend fun off(device: String)
}

/** [StaffBackend] over PostgREST, as the signed-in account — RLS decides. */
class SupabaseStaffBackend internal constructor(private val rest: SupabaseRest) : StaffBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun isStaff(): Boolean {
        val staff = rest.rpc("is_staff", buildJsonObject {}) == "true"
        // What the site does on every staff visit: a driver added by email
        // becomes this account for good. Its failure changes nothing here.
        if (staff) runCatching { rest.rpc("claim_staff_account", buildJsonObject {}) }
        return staff
    }

    /**
     * What the queue reads, newest columns first: each one a database turns
     * out not to have yet is dropped for good, oldest last.
     * [StaffOrder.SOURCE_COLUMNS] (20261004040000_phone_orders), then
     * [StaffOrder.NO_SHOW_COLUMNS] (20261004030000_no_shows), then
     * [StaffOrder.QUEUE_COLUMNS] (20261003160000_kitchen_capacity: no backlog
     * to wait for, so the estimate of before), then [StaffOrder.COLUMNS].
     */
    private val columns = ColumnLadder(QUEUE_SELECTS)

    override suspend fun openOrders(): List<StaffOrder> = columns.read { openOrders(it) }

    private suspend fun openOrders(columns: String): List<StaffOrder> =
        orders(columns) {
            addQueryParameter("status", "in.(new,confirmed,on_the_way)")
            // Right away first, then in the order they have to be cooked;
            // the screen sorts by when each is due.
            addQueryParameter("order", "scheduled_for.asc.nullsfirst,created_at.asc")
            addQueryParameter("limit", QUEUE_LIMIT.toString())
        }

    private suspend fun orders(columns: String, query: HttpUrl.Builder.() -> Unit): List<StaffOrder> {
        val url = rest.endpoint("rest/v1/orders").addQueryParameter("select", columns).apply(query).build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rest.rejected(response.code, body, "orders")
            json.decodeFromString(ListSerializer(StaffOrder.serializer()), body)
        }
    }

    override suspend fun move(order: StaffOrder, step: OrderStep) {
        if (step is OrderStep.Delay) return delay(order, step.minutes)
        if (step is OrderStep.Pack || step is OrderStep.Unpack) return packing(order, step)
        val url =
            rest.endpoint("rest/v1/orders")
                .addQueryParameter("id", "eq.${order.id}")
                // Only from where this device saw it: a second phone, up to
                // a poll behind, cannot send back out an order already
                // delivered.
                .addQueryParameter("status", "eq.${order.status.column}")
                .addQueryParameter("select", "id")
                .build()
        val body = stepBody(step).toRequestBody()
        val moved =
            rest.call({ it.url(url).patch(body).header("Prefer", "return=representation") }) { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    val error = rest.rejected(response.code, text, "order")
                    // The database refusing a step that is not one /orders
                    // offers: the order is somewhere else by now.
                    if (error is BackendException && error.code == "HB412") throw OrderMovedException()
                    // A "nicht angetroffen" the rule does not allow (#83): no
                    // longer on its way, a pickup, or already cancelled.
                    if (error is BackendException && error.code == "HB466") throw OrderMovedException()
                    if (error is BackendException && error.code == "HB438") throw PaymentLockedException()
                    throw error
                }
                json.decodeFromString(ListSerializer(IdRow.serializer()), text)
            }
        if (moved.isEmpty()) throw OrderMovedException()
    }

    /**
     * Sends the minutes to add, never the total: the database adds them to
     * whatever the order has by then, so two phones tapping +10 at once make
     * +20, not +10 twice over.
     */
    private suspend fun delay(order: StaffOrder, minutes: Int) {
        try {
            rest.rpc("delay_order", buildJsonObject {
                put("p_order_id", order.id)
                put("p_minutes", minutes)
            })
        } catch (e: BackendException) {
            // Not accepted any more, at its three hours, or gone.
            if (e.code == "HB435" || e.code == "P0002") throw NotDelayableException()
            throw e
        }
    }

    /**
     * "Fertig" or "Doch nicht fertig", by the database's own functions: they
     * set packed_at to the database clock and keep the first time when two
     * phones tap at once, so nothing but the order is sent.
     */
    private suspend fun packing(order: StaffOrder, step: OrderStep) {
        try {
            rest.rpc(Packing.function(step), Packing.body(order))
        } catch (e: BackendException) {
            // Not a confirmed delivery any more (HB458), or gone (P0002).
            if (e.code == "HB458" || e.code == "P0002") throw NotPackableException()
            if (e.missingFunction) throw PackingUnavailableException()
            throw e
        }
    }

    override suspend fun delayOpen(minutes: Int): Int {
        val body =
            try {
                rest.rpc("delay_open_orders", buildJsonObject { put("p_minutes", minutes) })
            } catch (e: BackendException) {
                if (e.code == "HB435") throw InvalidSettingException(e.message ?: "HB435")
                throw e
            }
        return json.decodeFromString(DelayedRow.serializer(), body).delayed
    }

    override suspend fun resetNoShows(order: StaffOrder): Int {
        val body =
            try {
                rest.rpc("reset_no_shows", buildJsonObject { put("p_order_id", order.id) })
            } catch (e: BackendException) {
                if (e.code == "P0002") throw NoShowAccountGoneException()
                if (e.missingFunction) throw NeedsServerUpdateException(NoShows.MIGRATION)
                throw e
            }
        return body.trim().toIntOrNull() ?: 0
    }

    override suspend fun prepMinutes(): Map<Long, Int> {
        val url = rest.endpoint("rest/v1/menu_items").addQueryParameter("select", "id,prep_minutes").build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rest.rejected(response.code, body, "menu")
            json.decodeFromString(ListSerializer(PrepRow.serializer()), body).associate { it.id to it.prepMinutes }
        }
    }

    override suspend fun seen(device: DeviceReport) {
        try {
            val version = device.version?.trim()?.takeIf { it.isNotEmpty() }
            try {
                rest.rpc("staff_app_seen", seenBody(device, version))
            } catch (e: BackendException) {
                // The site's database without 20261004070000_device_version
                // knows no p_version: said again without it.
                if (version == null || !(e.missingFunction || e.message.orEmpty().contains("p_version"))) throw e
                rest.rpc("staff_app_seen", seenBody(device, null))
            }
        } catch (e: BackendException) {
            if (!e.missingFunction) throw e
            // The site's database without 20261001120000_staff_app_devices:
            // nothing to be listed in, but whether this is still staff is
            // worth knowing all the same.
            if (!isStaff()) throw NotAllowedException()
        }
    }

    private fun seenBody(device: DeviceReport, version: String?): JsonObject = buildJsonObject {
        put("p_device", device.id)
        put("p_label", device.label)
        put("p_alarm", device.alarm)
        if (version != null) put("p_version", version)
    }

    override suspend fun off(device: String) {
        try {
            rest.rpc("staff_app_off", buildJsonObject { put("p_device", device) })
        } catch (e: BackendException) {
            if (!e.missingFunction) throw e
        }
    }

    private fun stepBody(step: OrderStep): JsonObject = buildJsonObject {
        put("status", checkNotNull(step.to) { "not a status step: $step" }.column)
        when (step) {
            is OrderStep.Accept -> put("eta_minutes", step.etaMinutes)
            is OrderStep.Done -> {
                // Left out rather than null: an order paid online keeps
                // "online", and without a method the Kassensturz says
                // "unbekannt", as for Telegram's button.
                step.payment?.let { put("payment_method", it.wire) }
                step.device?.trim()?.takeIf { it.isNotEmpty() }?.let { put("delivered_device", it) }
            }
            is OrderStep.Cancel -> {
                val reason = step.reason
                if (reason == null) put("cancel_reason", JsonNull)
                else put("cancel_reason", json.encodeToJsonElement(CancelReasonSerializer, reason))
            }
            else -> Unit
        }
    }

    @Serializable private class IdRow(val id: String)

    @Serializable private class DelayedRow(val delayed: Int)

    @Serializable
    private class PrepRow(val id: Long, @SerialName("prep_minutes") val prepMinutes: Int)

    private companion object {
        /** More open orders than this at once is not an evening that exists. */
        const val QUEUE_LIMIT = 100

        /** What the queue reads, newest database first: see [columns]. */
        val QUEUE_SELECTS =
            listOf(StaffOrder.SOURCE_COLUMNS, StaffOrder.NO_SHOW_COLUMNS, StaffOrder.QUEUE_COLUMNS, StaffOrder.COLUMNS)
    }
}

/**
 * What "Fertig" sends (20261003180000_packed_step.sql in hamza-bistro-web):
 * order_packed or order_unpacked, with the order's id and nothing else.
 */
object Packing {
    /** The function [step] calls: an [OrderStep.Pack] or an [OrderStep.Unpack]. */
    fun function(step: OrderStep): String =
        when (step) {
            OrderStep.Pack -> "order_packed"
            OrderStep.Unpack -> "order_unpacked"
            else -> throw IllegalArgumentException("not a packing step: $step")
        }

    fun body(order: StaffOrder): JsonObject = buildJsonObject { put("p_order_id", order.id) }
}

/** PostgREST's answer for a function the database does not have (yet). */
internal val BackendException.missingFunction: Boolean
    get() = status == 404 || code == "PGRST202"

/** Postgres's answer for a column the database does not have (yet). */
internal val BackendException.missingColumn: Boolean
    get() = code == "42703"

/**
 * Column lists for one read, newest database first, each one older than the
 * one before it. A read that hits a column the database does not have (yet)
 * steps down to the first list without that column — straight past the
 * lists between that have it too — and stays there for good: a database
 * does not lose a column. One it cannot name steps down one.
 */
internal class ColumnLadder(private val selects: List<String>) {
    @Volatile private var level = 0

    /** The columns read now, for tests. */
    val current: String
        get() = selects[level]

    suspend fun <T> read(block: suspend (String) -> T): T {
        while (true) {
            val at = level
            try {
                return block(selects[at])
            } catch (e: BackendException) {
                if (!e.missingColumn || at == selects.lastIndex) throw e
                // Another read may have stepped down meanwhile; never back up.
                val next = next(at, missingColumnName(e.message))
                if (level < next) level = next
            }
        }
    }

    private fun next(at: Int, column: String?): Int {
        if (column == null) return at + 1
        return (at + 1..selects.lastIndex).firstOrNull { column !in selects[it].split(",") } ?: (at + 1)
    }

    companion object {
        private val MISSING = Regex("""column (?:"?\w+"?\.)?"?(\w+)"? does not exist""")

        /** "column orders.kitchen_slot does not exist" → "kitchen_slot"; null when it names none. */
        fun missingColumnName(message: String?): String? = message?.let { MISSING.find(it)?.groupValues?.get(1) }
    }
}
