package de.hamzabistro.printstation.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync

/** An accepted order no station has printed. The ticket itself comes from [PrintBackend.ticket]. */
data class QueuedOrder(val id: String, val number: Long)

/**
 * What claim_print() answers — 20260930150000_order_printing.sql in
 * hamza-bistro-web.
 */
enum class Claim {
    /** This station prints it, then says how it went. */
    CLAIMED,

    /** A ticket came out already, here or elsewhere. */
    PRINTED,

    /** Another station took it less than a minute ago. */
    BUSY,

    /** No longer accepted, or gone: nothing to print. */
    MOVED,
}

/** The print protocol shared by every printing device, and the ticket to print. */
interface PrintBackend {
    /** Whether the signed-in account is staff or a print account. */
    suspend fun canPrint(): Boolean

    /** Accepted orders not printed yet, in the order the kitchen cooks them. */
    suspend fun openOrders(): List<QueuedOrder>

    suspend fun claim(order: String): Claim

    /** Printed: the ticket came out. Not printed: the claim is given back. */
    suspend fun finish(order: String, printed: Boolean)

    /** Registers this station, or says it is still there. */
    suspend fun seen(station: String, label: String)

    /** Takes this station off the list: printing was switched off here. */
    suspend fun off(station: String)

    /** The order's ticket as ESC/POS bytes, or null when it is no longer there to print. */
    suspend fun ticket(order: String, lang: String): ByteArray?

    /** The test ticket, for choosing a printer. */
    suspend fun testTicket(lang: String): ByteArray
}

/**
 * [PrintBackend] over Supabase: PostgREST for the queue and the protocol,
 * the print-ticket edge function for the bytes. Every call carries the
 * signed-in account's own token, so the database's policies decide.
 */
class SupabasePrintBackend(
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val sessions: SessionManager,
) : PrintBackend {
    override suspend fun canPrint(): Boolean = rpc("can_print", buildJsonObject {}) == "true"

    override suspend fun openOrders(): List<QueuedOrder> {
        val url =
            config.endpoint("rest/v1/orders")
                .addQueryParameter("select", "id,order_number")
                .addQueryParameter("status", "eq.confirmed")
                .addQueryParameter("printed_at", "is.null")
                // As /orders lists them: right away first, then by when they came in.
                .addQueryParameter("order", "scheduled_for.asc.nullsfirst,created_at.asc")
                .addQueryParameter("limit", QUEUE_LIMIT.toString())
                .build()
        return call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rejected(response.code, body, "orders")
            json.decodeFromString(ListSerializer(OrderRow.serializer()), body).map {
                QueuedOrder(it.id, it.orderNumber)
            }
        }
    }

    override suspend fun claim(order: String): Claim {
        val answer = rpc("claim_print", buildJsonObject { put("p_order_id", order) })
        return when (answer.trim('"')) {
            "claimed" -> Claim.CLAIMED
            "printed" -> Claim.PRINTED
            "busy" -> Claim.BUSY
            "moved" -> Claim.MOVED
            else -> throw BackendException(200, "claim_print answered something unknown")
        }
    }

    override suspend fun finish(order: String, printed: Boolean) {
        rpc("finish_print", buildJsonObject {
            put("p_order_id", order)
            put("p_printed", printed)
        })
    }

    override suspend fun seen(station: String, label: String) {
        rpc("print_station_seen", buildJsonObject {
            put("p_station", station)
            put("p_label", label)
        })
    }

    override suspend fun off(station: String) {
        rpc("print_station_off", buildJsonObject { put("p_station", station) })
    }

    override suspend fun ticket(order: String, lang: String): ByteArray? =
        printTicket(buildJsonObject {
            put("order", order)
            put("lang", lang)
        })

    override suspend fun testTicket(lang: String): ByteArray =
        printTicket(buildJsonObject {
            put("test", true)
            put("lang", lang)
        }) ?: throw BackendException(404, "print-ticket has no test ticket")

    private suspend fun printTicket(body: JsonObject): ByteArray? {
        val url = config.endpoint("functions/v1/print-ticket").build()
        return call({ it.url(url).post(body.toRequestBody()) }) { response ->
            when {
                response.code == 404 -> null
                response.code == 403 -> throw NotAllowedException()
                !response.isSuccessful ->
                    throw rejected(response.code, response.body.string(), "print-ticket")
                response.header("Content-Type")?.startsWith("application/octet-stream") != true ->
                    throw BackendException(response.code, "print-ticket did not answer with a ticket")
                else -> readTicket(response)
            }
        }
    }

    /**
     * A ticket is a kilobyte or two. Anything much larger is not a ticket,
     * and is not sent to the printer to find out.
     */
    private fun readTicket(response: Response): ByteArray {
        val length = response.body.contentLength()
        if (length > MAX_TICKET_BYTES) throw BackendException(response.code, "ticket too large")
        val bytes = response.body.source().use { source ->
            source.request(MAX_TICKET_BYTES + 1L)
            source.buffer.readByteArray(minOf(source.buffer.size, MAX_TICKET_BYTES + 1L))
        }
        if (bytes.size > MAX_TICKET_BYTES) throw BackendException(response.code, "ticket too large")
        if (bytes.isEmpty()) throw BackendException(response.code, "empty ticket")
        return bytes
    }

    /** A database function; its answer as JSON text ("" for none). */
    private suspend fun rpc(name: String, args: JsonObject): String {
        val url = config.endpoint("rest/v1/rpc/$name").build()
        return call({ it.url(url).post(args.toRequestBody()) }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rejected(response.code, body, name)
            body
        }
    }

    /**
     * Sends a request as the signed-in account. A 401 is tried once more
     * with a fresh token: the one it had may have run out on the way.
     * [read] reads the body, which blocks, hence the IO dispatcher.
     */
    private suspend fun <T> call(
        build: (Request.Builder) -> Request.Builder,
        read: (Response) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val token = sessions.accessToken()
            val first = send(build, token)
            if (first.code != 401) return@withContext first.use(read)
            first.close()
            sessions.expire(token)
            send(build, sessions.accessToken()).use(read)
        }

    private suspend fun send(build: (Request.Builder) -> Request.Builder, token: String): Response {
        val request =
            build(Request.Builder())
                .header("apikey", config.anonKey)
                .header("Authorization", "Bearer $token")
                .build()
        return http.newCall(request).executeAsync()
    }

    private fun rejected(status: Int, body: String, what: String): Exception {
        val message = errorField(body, "message", "msg", "error") ?: "HTTP $status"
        // How the printing functions refuse an account that is neither
        // staff nor a print account.
        if (message == "staff only") return NotAllowedException()
        return BackendException(status, "$what: $message")
    }

    @Serializable
    private class OrderRow(val id: String, @SerialName("order_number") val orderNumber: Long)

    private companion object {
        /** More accepted and unprinted than this at once is not an evening that exists. */
        const val QUEUE_LIMIT = 50
        const val MAX_TICKET_BYTES = 64 * 1024
    }
}
