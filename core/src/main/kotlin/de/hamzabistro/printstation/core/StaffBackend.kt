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
    /** "Küche", "Ali", "Kasse" — how /orders/settings lists it. */
    val label: String,
    /** How it alarms about a new order: "loop", "once" or "off". */
    val alarm: String,
)

/** The queue as staff work it: read it, move an order on, say this device is listening. */
interface StaffBackend {
    /** Whether the signed-in account is staff — and binds a staff row added by email to it. */
    suspend fun isStaff(): Boolean

    /** Every order still in play: new, accepted, on its way. */
    suspend fun openOrders(): List<StaffOrder>

    /**
     * Moves [order] one [step] on — but only from where this device saw it.
     * Throws [OrderMovedException] when it had moved on meanwhile.
     */
    suspend fun move(order: StaffOrder, step: OrderStep)

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

    override suspend fun openOrders(): List<StaffOrder> =
        orders {
            addQueryParameter("status", "in.(new,confirmed,on_the_way)")
            // Right away first, then in the order they have to be cooked;
            // the screen sorts by when each is due.
            addQueryParameter("order", "scheduled_for.asc.nullsfirst,created_at.asc")
            addQueryParameter("limit", QUEUE_LIMIT.toString())
        }

    private suspend fun orders(query: HttpUrl.Builder.() -> Unit): List<StaffOrder> {
        val url = rest.endpoint("rest/v1/orders").addQueryParameter("select", StaffOrder.COLUMNS).apply(query).build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rest.rejected(response.code, body, "orders")
            json.decodeFromString(ListSerializer(StaffOrder.serializer()), body)
        }
    }

    override suspend fun move(order: StaffOrder, step: OrderStep) {
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
                    throw error
                }
                json.decodeFromString(ListSerializer(IdRow.serializer()), text)
            }
        if (moved.isEmpty()) throw OrderMovedException()
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
            rest.rpc("staff_app_seen", buildJsonObject {
                put("p_device", device.id)
                put("p_label", device.label)
                put("p_alarm", device.alarm)
            })
        } catch (e: BackendException) {
            if (!e.missingFunction) throw e
            // The site's database without 20261001120000_staff_app_devices:
            // nothing to be listed in, but whether this is still staff is
            // worth knowing all the same.
            if (!isStaff()) throw NotAllowedException()
        }
    }

    override suspend fun off(device: String) {
        try {
            rest.rpc("staff_app_off", buildJsonObject { put("p_device", device) })
        } catch (e: BackendException) {
            if (!e.missingFunction) throw e
        }
    }

    private fun stepBody(step: OrderStep): JsonObject = buildJsonObject {
        put("status", step.to.column)
        when (step) {
            is OrderStep.Accept -> put("eta_minutes", step.etaMinutes)
            is OrderStep.Cancel -> {
                val reason = step.reason
                if (reason == null) put("cancel_reason", JsonNull)
                else put("cancel_reason", json.encodeToJsonElement(CancelReason.serializer(), reason))
            }
            else -> Unit
        }
    }

    @Serializable private class IdRow(val id: String)

    @Serializable
    private class PrepRow(val id: Long, @SerialName("prep_minutes") val prepMinutes: Int)

    private companion object {
        /** More open orders than this at once is not an evening that exists. */
        const val QUEUE_LIMIT = 100
    }
}

/** PostgREST's answer for a function the database does not have (yet). */
internal val BackendException.missingFunction: Boolean
    get() = status == 404 || code == "PGRST202"
