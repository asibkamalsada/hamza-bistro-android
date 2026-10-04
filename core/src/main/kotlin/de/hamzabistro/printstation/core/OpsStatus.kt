package de.hamzabistro.printstation.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * One row of ops_status, or of ops_overview() (20261003210000_ops_status.sql
 * in hamza-bistro-web, #92): when a part of the shop that fails quietly last
 * worked and last failed, and why. [stale] — a pg_cron job that stopped
 * running — only ops_overview() says; the plain table has no such column.
 */
@Serializable
data class OpsRow(
    val source: String,
    @SerialName("last_ok_at") @Serializable(with = InstantSerializer::class) val lastOkAt: Instant? = null,
    @SerialName("last_error_at") @Serializable(with = InstantSerializer::class) val lastErrorAt: Instant? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("is_cron") val isCron: Boolean = false,
    val stale: Boolean = false,
) {
    /** Failed since it last worked: the newer of its two times is the error. */
    val failing: Boolean
        get() = lastErrorAt != null && (lastOkAt == null || lastErrorAt.isAfter(lastOkAt))
}

/**
 * failed_customer_email(): the latest customer email of the last six hours
 * that did not go out, not even on its one retry. [kind] is `received`,
 * `confirmed`, `on_the_way`, `delivered`, `cancelled` or `delay-N`.
 */
@Serializable
data class FailedEmail(
    @SerialName("order_number") val orderNumber: Long,
    val kind: String = "",
    val error: String = "",
    @SerialName("failed_at") @Serializable(with = InstantSerializer::class) val failedAt: Instant? = null,
)

/** What one row of "Überwachung" says, before it is put in words. */
sealed interface OpsVerdict {
    val ok: Boolean

    /** "OK 18:32", or "OK 18:32 — letzter Fehler 17:05" when [lastError] is set. */
    data class Ok(val at: Instant, val lastError: Instant?) : OpsVerdict {
        override val ok = true
    }

    /** "Fehler 18:32: …". */
    data class Failing(val at: Instant, val error: String) : OpsVerdict {
        override val ok = false
    }

    /** "Läuft nicht — zuletzt OK 18:32", or "— noch nie OK" without [lastOk]. */
    data class Stale(val lastOk: Instant?) : OpsVerdict {
        override val ok = false
    }

    /** "Noch nichts aufgezeichnet": a function nobody has called since the deploy. Not a failure. */
    data object Never : OpsVerdict {
        override val ok = true
    }

    /** The heartbeat with neither time: "Nicht eingerichtet" — no URL in Vault. Not a failure. */
    data object NotSetUp : OpsVerdict {
        override val ok = true
    }
}

/** The site's ops-health.ts: the order of the rows, and what each says. */
object OpsHealth {
    /** The webhook that rings the phones: when it fails, this app is the one still listening. */
    const val NOTIFY_ORDER = "notify-order"
    const val HEARTBEAT = "heartbeat"

    /** The sources with a name of their own, in the order shown: what the kitchen notices first, first. */
    val ORDER: List<String> =
        listOf(
            NOTIFY_ORDER,
            "notify-customer",
            "telegram-bot",
            "cron:reminders",
            "cron:email-retry",
            "cron:retention",
            "cron:geocodes",
            "cron:history",
            "cron:daily-summary",
            HEARTBEAT,
        )

    /** The rows in [ORDER]; a source this app has no name for after them, by name. */
    fun sorted(rows: List<OpsRow>): List<OpsRow> {
        fun rank(source: String) = ORDER.indexOf(source).let { if (it < 0) ORDER.size else it }
        return rows.sortedWith(compareBy<OpsRow> { rank(it.source) }.thenBy { it.source })
    }

    /** What [row] says, read off whichever of its two times is newer — and a job that stopped is not working either. */
    fun verdict(row: OpsRow): OpsVerdict {
        val ok = row.lastOkAt
        val failed = row.lastErrorAt
        if (ok == null && failed == null) {
            return when {
                row.stale -> OpsVerdict.Stale(null)
                row.source == HEARTBEAT -> OpsVerdict.NotSetUp
                else -> OpsVerdict.Never
            }
        }
        if (row.failing) return OpsVerdict.Failing(failed!!, row.lastError ?: "?")
        if (row.stale) return OpsVerdict.Stale(ok)
        return OpsVerdict.Ok(ok!!, failed)
    }

    /**
     * "Benachrichtigungen gestört": since when notify-order has been
     * failing, or null while it works (or is not known).
     */
    fun notifyFailingSince(rows: List<OpsRow>?): Instant? =
        rows?.firstOrNull { it.source == NOTIFY_ORDER }?.takeIf { it.failing }?.lastErrorAt
}

/**
 * The monitoring, over PostgREST as the signed-in staff account. A database
 * without it (PGRST202 for the functions, a missing ops_status) answers
 * null: no data, and no error.
 */
interface OpsBackend {
    /** ops_overview(): every row, the pg_cron rows brought up to date, with [OpsRow.stale]. For "Überwachung". */
    suspend fun overview(): List<OpsRow>?

    /** The ops_status table as it is — cheaper, for polling with the queue. */
    suspend fun status(): List<OpsRow>?

    /** The latest customer email that failed on its retry too, or null for none. */
    suspend fun failedEmail(): FailedEmail?
}

class SupabaseOpsBackend internal constructor(private val rest: SupabaseRest) : OpsBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun overview(): List<OpsRow>? =
        rpc("ops_overview")?.let { json.decodeFromString(ListSerializer(OpsRow.serializer()), it) }

    override suspend fun status(): List<OpsRow>? {
        val url = rest.endpoint("rest/v1/ops_status").addQueryParameter("select", STATUS_COLUMNS).build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "ops_status")
                if (e is BackendException && e.missingTable) return@call null
                throw e
            }
            json.decodeFromString(ListSerializer(OpsRow.serializer()), body)
        }
    }

    override suspend fun failedEmail(): FailedEmail? =
        rpc("failed_customer_email")?.let { json.decodeFromString(ListSerializer(FailedEmail.serializer()), it).firstOrNull() }

    private suspend fun rpc(name: String): String? =
        try {
            rest.rpc(name, JsonObject(emptyMap()))
        } catch (e: BackendException) {
            if (e.missingTable) null else throw e
        }

    internal companion object {
        const val STATUS_COLUMNS = "source,last_ok_at,last_error_at,last_error"
    }
}
