package de.hamzabistro.printstation.core

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient

/** What today's delivered orders came to, for the end of a shift. */
data class Takings(val count: Int, val total: Double)

/**
 * The orders behind the queue: the recent ones whatever became of them, and
 * what today came to — "Letzte Bestellungen" on the site's /orders. For the
 * call that starts "about my order yesterday", and for cashing up.
 */
interface HistoryBackend {
    /**
     * Everything recent, finished or not, newest first. The retention job
     * clears names and addresses 30 days after an order, so older ones come
     * back without them; that is intended.
     */
    suspend fun recent(): List<StaffOrder>

    /**
     * Today's delivered orders on a Leipzig clock, and their total. Counted by
     * when the order was placed, which for a shop that closes before midnight
     * is the same day.
     */
    suspend fun takings(now: Instant): Takings
}

/** [HistoryBackend] over PostgREST, as the signed-in account — staff read every order. */
class SupabaseHistoryBackend internal constructor(
    private val rest: SupabaseRest,
    private val zone: ZoneId = AlarmPolicy.LEIPZIG,
) : HistoryBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun recent(): List<StaffOrder> {
        val url =
            rest.endpoint("rest/v1/orders")
                .addQueryParameter("select", StaffOrder.COLUMNS)
                .addQueryParameter("order", "created_at.desc")
                .addQueryParameter("limit", HISTORY_LIMIT.toString())
                .build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rest.rejected(response.code, body, "orders")
            json.decodeFromString(ListSerializer(StaffOrder.serializer()), body)
        }
    }

    override suspend fun takings(now: Instant): Takings {
        val midnight = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val url =
            rest.endpoint("rest/v1/orders")
                .addQueryParameter("select", "total")
                .addQueryParameter("status", "eq.delivered")
                .addQueryParameter("created_at", "gte.$midnight")
                .build()
        val rows =
            rest.call({ it.url(url).get() }) { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw rest.rejected(response.code, body, "orders")
                json.decodeFromString(ListSerializer(TotalRow.serializer()), body)
            }
        // In cents, so forty orders of 19,90 € do not come to 795,9999.
        return Takings(rows.size, Math.round(rows.sumOf { it.total } * 100) / 100.0)
    }

    @Serializable private class TotalRow(val total: Double)

    private companion object {
        /** A couple of busy evenings' worth, as on the site. */
        const val HISTORY_LIMIT = 50
    }
}
