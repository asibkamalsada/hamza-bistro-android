package de.hamzabistro.printstation.core

import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * A kept file of the till: a daily closing's or an export's DSFinV-K ZIP
 * (bucket dsfinvk), or a piece of the TSE's log (bucket tse-tar). [path] is
 * null until the file is written.
 */
interface NachschauFile {
    val bucket: String
    val path: String?
    val bytes: Long?
    val sha256: String?

    /** The file's own name, the last part of its path. */
    val fileName: String?
        get() = path?.substringAfterLast('/')
}

/** One closed business day (Z-Bon): built, then exported once its ZIP is kept. */
@Serializable
data class NachschauClosing(
    @SerialName("business_date") val businessDate: String,
    @SerialName("closing_number") val closingNumber: Int,
    val env: String,
    val state: String,
    val failed: Boolean = false,
    override val bucket: String = Nachschau.DSFINVK_BUCKET,
    override val path: String? = null,
    override val bytes: Long? = null,
    override val sha256: String? = null,
) : NachschauFile

/** A DSFinV-K export somebody asked for: requested, then stored. */
@Serializable
data class NachschauExport(
    val id: Long,
    @SerialName("date_from") val dateFrom: String,
    @SerialName("date_to") val dateTo: String,
    val state: String,
    val failed: Boolean = false,
    val dismissed: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    override val bucket: String = Nachschau.DSFINVK_BUCKET,
    override val path: String? = null,
    override val bytes: Long? = null,
    override val sha256: String? = null,
) : NachschauFile {
    /** Still being made: worth asking again. */
    val inProgress: Boolean
        get() = state == "requested" && !failed && !dismissed
}

/** One export of the TSE's log; [fromTime] null is the TSE's first entry. */
@Serializable
data class NachschauTar(
    val id: Long,
    val serial: String,
    val env: String,
    val vendor: String,
    @SerialName("from_time") val fromTime: String? = null,
    @SerialName("to_time") val toTime: String,
    val state: String,
    val failed: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    override val bucket: String = Nachschau.TAR_BUCKET,
    override val path: String? = null,
    override val bytes: Long? = null,
    override val sha256: String? = null,
) : NachschauFile

/**
 * What a Kassen-Nachschau is handed for a range of business days
 * (tse_nachschau() in hamza-bistro-web's 20261127190000_kassen_nachschau.sql):
 * the closings of the range, the 20 newest exports whatever their range, and
 * every TAR that overlaps it.
 */
@Serializable
data class Nachschau(
    val from: String,
    val to: String,
    val closings: List<NachschauClosing> = emptyList(),
    val exports: List<NachschauExport> = emptyList(),
    val tars: List<NachschauTar> = emptyList(),
    /** How far the kept TARs reach; null when none of the range is kept. */
    @SerialName("tar_stored_until") val tarStoredUntil: String? = null,
) {
    val making: Boolean
        get() = exports.any { it.inProgress }

    companion object {
        const val DSFINVK_BUCKET = "dsfinvk"
        const val TAR_BUCKET = "tse-tar"

        /** How long a download link works: long enough to start, too short to pass on. */
        const val SIGNED_URL_SECONDS = 60

        /** The migration a database without tse_nachschau() is missing. */
        const val MIGRATION = "20261127190000_kassen_nachschau"

        /** The calendar month before the one [today] is in: what is usually asked for. */
        fun previousMonth(today: LocalDate): Pair<LocalDate, LocalDate> {
            val first = today.withDayOfMonth(1).minusMonths(1)
            return first to first.plusMonths(1).minusDays(1)
        }
    }
}

/** The database refused the range: one that ends before it starts, or includes today. */
class NachschauRangeException(message: String) : Exception(message)

interface NachschauBackend {
    /** Every file there is to hand over for [from]..[to]. */
    suspend fun load(from: LocalDate, to: LocalDate): Nachschau

    /** Asks for a DSFinV-K export of the closed days [from]..[to]; its id. */
    suspend fun requestExport(from: LocalDate, to: LocalDate): Long

    /**
     * A link to [file], signed by Storage for the signed-in staff member and
     * good for [Nachschau.SIGNED_URL_SECONDS]. The bucket's policy decides
     * who may sign; the link carries nothing but Storage's own token.
     */
    suspend fun signedUrl(file: NachschauFile): String
}

/** [NachschauBackend] over PostgREST and Storage, as the signed-in account. */
class SupabaseNachschauBackend internal constructor(private val rest: SupabaseRest) : NachschauBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun load(from: LocalDate, to: LocalDate): Nachschau {
        val body = call("tse_nachschau", from, to)
        return json.decodeFromString(Nachschau.serializer(), body)
    }

    override suspend fun requestExport(from: LocalDate, to: LocalDate): Long =
        call("tse_dsfinvk_export", from, to).trim().toLong()

    override suspend fun signedUrl(file: NachschauFile): String {
        val path = file.path ?: throw IllegalStateException("not kept yet")
        val url = rest.endpoint("storage/v1/object/sign/${file.bucket}").addPathSegments(path).build()
        val body = buildJsonObject { put("expiresIn", Nachschau.SIGNED_URL_SECONDS) }
        val signed =
            rest.call({ it.url(url).post(body.toRequestBody()) }) { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw rest.rejected(response.code, text, "sign")
                json.decodeFromString(SignedRow.serializer(), text).signedURL
            }
        // Storage answers with a path below /storage/v1, the token in its query.
        return rest.endpoint("storage/v1").build().toString().trimEnd('/') + signed
    }

    private suspend fun call(name: String, from: LocalDate, to: LocalDate): String =
        try {
            rest.rpc(name, buildJsonObject {
                put("p_from", from.toString())
                put("p_to", to.toString())
            })
        } catch (e: BackendException) {
            if (e.missingFunction) throw NeedsServerUpdateException(Nachschau.MIGRATION)
            if (e.code == RANGE_REFUSED) throw NachschauRangeException(e.message ?: RANGE_REFUSED)
            throw e
        }

    @Serializable private class SignedRow(val signedURL: String)

    private companion object {
        /** invalid_parameter_value: tse_nachschau's and tse_dsfinvk_export's refused range. */
        const val RANGE_REFUSED = "22023"
    }
}
