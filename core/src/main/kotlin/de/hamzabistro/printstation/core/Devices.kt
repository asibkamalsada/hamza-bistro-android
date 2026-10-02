package de.hamzabistro.printstation.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * A device on shift in this app, as staff_app_devices() lists it
 * (20261001120000_staff_app_devices.sql in hamza-bistro-web). It rings by
 * itself, without a push.
 */
@Serializable
data class AppDevice(
    val id: String,
    /** Signed in with the account looking at the list. */
    val mine: Boolean = false,
    val label: String = "",
    /** How it rings about a new order: "loop", "once" or "off". */
    val alarm: String = "loop",
    @SerialName("started_at") @Serializable(with = InstantSerializer::class) val startedAt: Instant,
    @SerialName("last_seen_at") @Serializable(with = InstantSerializer::class) val lastSeenAt: Instant,
) {
    /**
     * Heard from recently enough to count. A device on shift says so once a
     * minute; three minutes is two misses, which is a phone that is off,
     * flat, or had the app killed by a battery saver.
     */
    fun listening(now: Instant): Boolean = Duration.between(lastSeenAt, now) < SILENT_AFTER

    /** Whether it would make a sound about the next order. */
    fun rings(now: Instant): Boolean = alarm != "off" && listening(now)

    companion object {
        val SILENT_AFTER: Duration = Duration.ofMinutes(3)
    }
}

/**
 * A phone or browser that gets the website's Web Push, as
 * staff_push_devices() lists it. Read here only to say who will hear about
 * the next order; its settings belong to the browser it lives in.
 */
@Serializable
data class PushDevice(
    val mine: Boolean = false,
    val label: String = "",
    @SerialName("notify_new") val notifyNew: Boolean = true,
    @SerialName("notify_reminders") val notifyReminders: Boolean = true,
    @SerialName("notify_preorders") val notifyPreorders: Boolean = true,
    @SerialName("notify_unprinted") val notifyUnprinted: Boolean = false,
    val loud: Boolean = false,
    @SerialName("quiet_from") @Serializable(with = TimeOfDaySerializer::class) val quietFrom: LocalTime? = null,
    @SerialName("quiet_to") @Serializable(with = TimeOfDaySerializer::class) val quietTo: LocalTime? = null,
    @SerialName("last_sent_at") @Serializable(with = InstantSerializer::class) val lastSentAt: Instant? = null,
    @SerialName("last_error_at") @Serializable(with = InstantSerializer::class) val lastErrorAt: Instant? = null,
    @SerialName("last_error") val lastError: String? = null,
) {
    /** The last push to it failed, and nothing has got through since. */
    val failing: Boolean
        get() = lastErrorAt != null && (lastSentAt == null || lastErrorAt.isAfter(lastSentAt))
}

/** A device that prints every accepted order by itself, as print_stations() lists it. */
@Serializable
data class PrintStationRow(
    val id: String,
    /** This device. */
    @SerialName("this_station") val thisStation: Boolean = false,
    val label: String = "",
    /** Since when it has been meant to print every accepted order. */
    @SerialName("registered_at") @Serializable(with = InstantSerializer::class) val registeredAt: Instant? = null,
    @SerialName("last_seen_at") @Serializable(with = InstantSerializer::class) val lastSeenAt: Instant,
)

/** How the delivery address check has been going: address_check_health(). */
@Serializable
data class AddressCheckHealth(
    /** False: the checkout takes an address the map does not know, priced by its postcode. */
    @SerialName("require_check") val requireCheck: Boolean = true,
    @SerialName("last_found_at") @Serializable(with = InstantSerializer::class) val lastFoundAt: Instant? = null,
    @SerialName("last_not_found_at") @Serializable(with = InstantSerializer::class) val lastNotFoundAt: Instant? = null,
    @SerialName("last_error_at") @Serializable(with = InstantSerializer::class) val lastErrorAt: Instant? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_unconfigured_at") @Serializable(with = InstantSerializer::class) val lastUnconfiguredAt: Instant? = null,
    /** When the fallback geocoder last had to answer, and what the first one said. */
    @SerialName("last_fallback_at") @Serializable(with = InstantSerializer::class) val lastFallbackAt: Instant? = null,
    @SerialName("last_fallback") val lastFallback: String? = null,
    @SerialName("deliveries_7d") val deliveries7d: Int = 0,
    @SerialName("unverified_7d") val unverified7d: Int = 0,
) {
    /**
     * One line on whether the geocoder works, read off whichever outcome is
     * newest — describeAddressHealth() of the site. An error newer than the
     * last address found is a geocoder that has stopped working.
     */
    fun verdict(): AddressVerdict {
        val found = lastFoundAt.millis
        val failed = lastErrorAt.millis
        if (lastUnconfiguredAt.millis > maxOf(found, failed)) return AddressVerdict.Unconfigured
        if (failed > found) return AddressVerdict.Failing(lastErrorAt!!, lastError ?: "?")
        // Found, but by the fallback: the first geocoder is down, and every
        // lookup spends the fallback's quota — until the first one answers
        // a lookup by itself again.
        val fallback = lastFallbackAt.millis
        if (fallback > 0 && fallback >= maxOf(found, lastNotFoundAt.millis)) {
            return AddressVerdict.Fallback(lastFallbackAt!!, lastFallback ?: "?")
        }
        if (found > 0) return AddressVerdict.Ok(lastFoundAt!!)
        return AddressVerdict.NoData
    }

    private val Instant?.millis: Long
        get() = this?.toEpochMilli() ?: 0L
}

/** What [AddressCheckHealth.verdict] says. Only [Ok] is good news. */
sealed interface AddressVerdict {
    val ok: Boolean
        get() = this is Ok

    data class Ok(val lastFound: Instant) : AddressVerdict

    data class Failing(val at: Instant, val error: String) : AddressVerdict

    data class Fallback(val at: Instant, val error: String) : AddressVerdict

    data object Unconfigured : AddressVerdict

    data object NoData : AddressVerdict
}

/** What a live look-up of the shop's own address said: the check-address function's probe. */
@Serializable
data class AddressProbe(
    val ok: Boolean,
    /** "found", "not_found", "error", "busy" or "unconfigured". */
    val outcome: String,
    val zone: String? = null,
    @SerialName("postal_code") val postalCode: String? = null,
    val detail: String? = null,
    /** The host of the geocoder that answered. */
    val via: String? = null,
    /** What the geocoders before it said, when the first did not answer. */
    val skipped: String? = null,
    val ms: Long = 0,
)

/**
 * Who hears about the next order, which devices print, and whether the
 * address check works — the health half of the site's /orders/settings.
 */
interface DevicesBackend {
    /** Every device on shift in this app, the most recently heard from first. */
    suspend fun appDevices(): List<AppDevice>

    /** Takes a device off that list — lost, wiped or given away, it cannot end its own shift. */
    suspend fun removeAppDevice(id: String)

    /** The phones and browsers that get the website's pushes. */
    suspend fun pushDevices(): List<PushDevice>

    /** Every registered print station, [station] (this device) marked. */
    suspend fun printStations(station: String): List<PrintStationRow>

    /** Takes a print station off the list; one still printing puts itself back within a minute. */
    suspend fun removePrintStation(id: String)

    suspend fun addressHealth(): AddressCheckHealth

    /** Looks the shop's own address up, live, through the checkout's own function. */
    suspend fun testAddress(): AddressProbe
}

/** [DevicesBackend] over PostgREST and the check-address function, as the signed-in account. */
class SupabaseDevicesBackend internal constructor(private val rest: SupabaseRest) : DevicesBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun appDevices(): List<AppDevice> =
        try {
            json.decodeFromString(ListSerializer(AppDevice.serializer()), rest.rpc("staff_app_devices", buildJsonObject {}))
        } catch (e: BackendException) {
            // A database without the device list has none, rather than an
            // error on a screen that is about something else.
            if (e.missingFunction) emptyList() else throw e
        }

    override suspend fun removeAppDevice(id: String) {
        rest.rpc("staff_app_off", buildJsonObject { put("p_device", id) })
    }

    override suspend fun pushDevices(): List<PushDevice> =
        // No endpoint: this app is not one of them.
        json.decodeFromString(
            ListSerializer(PushDevice.serializer()),
            rest.rpc("staff_push_devices", buildJsonObject { put("p_endpoint", JsonNull) }),
        )

    override suspend fun printStations(station: String): List<PrintStationRow> =
        json.decodeFromString(
            ListSerializer(PrintStationRow.serializer()),
            rest.rpc("print_stations", buildJsonObject { put("p_station", station) }),
        )

    override suspend fun removePrintStation(id: String) {
        rest.rpc("print_station_off", buildJsonObject { put("p_station", id) })
    }

    override suspend fun addressHealth(): AddressCheckHealth =
        json.decodeFromString(AddressCheckHealth.serializer(), rest.rpc("address_check_health", buildJsonObject {}))

    override suspend fun testAddress(): AddressProbe {
        val url = rest.endpoint("functions/v1/check-address").build()
        val body = buildJsonObject { put("probe", true) }.toRequestBody()
        return rest.call({ it.url(url).post(body) }) { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw rest.rejected(response.code, text, "check-address")
            json.decodeFromString(AddressProbe.serializer(), text)
        }
    }
}

/** Postgres's time — "22:00:00" — as a [LocalTime]. */
object TimeOfDaySerializer : KSerializer<LocalTime> {
    override val descriptor = PrimitiveSerialDescriptor("TimeOfDay", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
}
