package de.hamzabistro.printstation.core

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * How an order came in, as orders.source spells it
 * (20261004040000_phone_orders.sql in hamza-bistro-web). Staff enter
 * [PHONE] and [COUNTER]; [WEB] is every order placed on the site.
 */
enum class OrderSource(val wire: String) {
    /** "Telefon": somebody called. */
    PHONE("phone"),

    /** "Vor Ort / Theke": somebody stood at the counter. */
    COUNTER("counter");

    companion object {
        /** orders.source of an order placed on the site, and of every order before the column. */
        const val WEB = "web"

        /** The source [wire] names; null for "web" or one this app does not know. */
        fun of(wire: String?): OrderSource? = entries.firstOrNull { it.wire == wire }
    }
}

/** "Abholung", "Vor Ort" or "Lieferung": what staff_place_order's p_fulfilment takes. */
enum class Fulfilment(val wire: String) {
    PICKUP("pickup"),

    /** Eaten here. Stored as a pickup with dine_in. */
    DINE_IN("dine_in"),
    DELIVERY("delivery");

    /** Whether the database wants a name and a phone number (HB468): somebody is called, or rung at the door. */
    val needsContact: Boolean
        get() = this != DINE_IN
}

/** One line of the basket: a dish, how many, the choices in the dialog's order, and its note. */
data class BasketLine(
    val dish: MenuDish,
    val qty: Int,
    /** In the dish's group order, then each group's own: as the site's dialog lists them. */
    val options: List<MenuOption> = emptyList(),
    /** "ohne Zwiebeln": at most [PhoneOrders.NOTE_MAX] characters. */
    val note: String = "",
) {
    /** The menu price with the choices, before the deal of the day; the dry run says what it comes to. */
    val unitPrice: Double
        get() = dish.price + options.sumOf { it.price }

    /** As an order line, for the ETA's cooking time. */
    val asItem: OrderItem
        get() = OrderItem(dish.id, dish.name, options.map { it.name }, unitPrice, dish.deposit, qty, note.trim().ifEmpty { null })
}

/** Everything typed into "Neue Bestellung" so far. */
data class PhoneOrderDraft(
    val source: OrderSource = OrderSource.PHONE,
    val fulfilment: Fulfilment = Fulfilment.PICKUP,
    val lines: List<BasketLine> = emptyList(),
    val name: String = "",
    val phone: String = "",
    val street: String = "",
    val postalCode: String = "",
    val city: String = PhoneOrders.DEFAULT_CITY,
    /** "Hinterhaus, 3. OG": for the driver at the door. */
    val addressNote: String = "",
    /** The order's note for the kitchen. */
    val notes: String = "",
    /** Null for right away; otherwise a time in the next seven days. */
    val scheduledFor: Instant? = null,
    /** "Telefonbestellung, Gebühr erlassen": no delivery fee, small-order fee or last-ring minimum. */
    val waiveFees: Boolean = false,
) {
    val delivery: Boolean
        get() = fulfilment == Fulfilment.DELIVERY

    /** How many dishes, counting each one. */
    val count: Int
        get() = lines.sumOf { it.qty }
}

/** What is still missing or wrong before the order can be priced or saved. */
enum class DraftProblem {
    /** Nothing in the basket. */
    EMPTY,

    /** No name, for a delivery or a pickup. */
    NAME,

    /** No phone number, for a delivery or a pickup. */
    PHONE,

    /** No street, for a delivery. */
    STREET,

    /** Not five digits, for a delivery. */
    POSTAL_CODE,

    /** No town, for a delivery. */
    CITY,

    /** A time that has passed, or is more than seven days ahead. */
    TIME,
}

/**
 * The one answer staff_place_order gives, priced or saved. On a dry run
 * [id] and [orderNumber] are null and nothing was inserted.
 */
@Serializable
data class PhoneOrderQuote(
    val id: String? = null,
    @SerialName("order_number") val orderNumber: Long? = null,
    val total: Double = 0.0,
    val subtotal: Double = 0.0,
    val deposits: Double = 0.0,
    @SerialName("delivery_fee") val deliveryFee: Double = 0.0,
    @SerialName("small_order_fee") val smallOrderFee: Double = 0.0,
    @SerialName("pickup_discount") val pickupDiscount: Double = 0.0,
    @SerialName("deal_discount") val dealDiscount: Double = 0.0,
    /** The tick took something off. */
    @SerialName("fees_waived") val feesWaived: Boolean = false,
    /** The euros the tick took off. */
    val waived: Double = 0.0,
    /** The ring a delivery was priced in; null for a pickup. */
    @SerialName("delivery_zone") val deliveryZone: String? = null,
    /** "postcode" when the map could not place the address. */
    @SerialName("delivery_zone_source") val deliveryZoneSource: String? = null,
    @SerialName("kitchen_slot") @Serializable(with = InstantSerializer::class) val kitchenSlot: Instant? = null,
    @SerialName("promised_at") @Serializable(with = InstantSerializer::class) val promisedAt: Instant? = null,
    /** "Küche voll in diesem Slot": the order goes in all the same. */
    @SerialName("over_capacity") val overCapacity: Boolean = false,
    @SerialName("dry_run") val dryRun: Boolean = false,
)

/** Why staff_place_order refused, by its error code. */
enum class PhoneOrderError(val code: String) {
    /** 42501: the account is not staff (any more). */
    NOT_STAFF("42501"),

    /** HB467: a source or fulfilment this app should never send. */
    BAD_VALUE("HB467"),

    /** HB468: name or phone missing for a delivery or a pickup. */
    CONTACT("HB468"),

    /** HB469: an order for now without minutes from 1 to 180. */
    ETA("HB469"),

    /** HB470: the time has passed, or is more than seven days ahead. */
    TIME("HB470"),

    /** HB471: the basket — empty, a quantity, a dish or option sold out or not belonging, a note. */
    BASKET("HB471"),

    /** HB472: the delivery address is incomplete. */
    ADDRESS_INCOMPLETE("HB472"),

    /** HB426: the address is outside the area. Not waivable. */
    OUTSIDE("HB426"),

    /** HB427: the last ring's minimum is not reached; the waiver lets it through. */
    MINIMUM("HB427"),

    /** HB428: the address has not been checked (in the last two hours). */
    UNCHECKED("HB428"),

    /** HB409: the total is not the one shown: prices or stock changed meanwhile. */
    TOTAL_CHANGED("HB409");

    companion object {
        fun of(code: String?): PhoneOrderError? = entries.firstOrNull { it.code == code }
    }
}

/** staff_place_order refused, for [reason]. [minimum] is the last ring's minimum for [PhoneOrderError.MINIMUM]. */
class PhoneOrderException(val reason: PhoneOrderError, message: String, val minimum: Double? = null) : Exception(message)

/** How far check-address got with an address, as the site's address-check.service.ts reads it. */
enum class AddressStatus {
    /** Geocoded: the zone is the ring it is in. */
    VERIFIED,

    /** Looked up and not found: priced on the postcode, marked on the order. */
    UNVERIFIED,

    /** Nothing on record yet — half typed, or the geocoder busy. staff_place_order refuses it (HB428). */
    UNCHECKED,
}

/** check-address's answer. [suggestedPostalCode] is the geocoder's postcode when it is not the one typed. */
data class AddressCheck(val zone: String?, val status: AddressStatus, val suggestedPostalCode: String? = null) {
    /** Outside every ring: nobody rides there, waiver or not. */
    val outside: Boolean
        get() = status == AddressStatus.VERIFIED && zone == "outside"
}

/**
 * The rules of the options dialog on the site's menu page, for a dish
 * entered on the tablet: the dish's groups in its own order, each group's
 * live choices in theirs; a "single" group is a required single choice,
 * starting on its first choice still on sale; a "multiple" group takes any
 * number of choices, none included. The database has no other limit on how
 * many may be picked. A sold-out choice cannot be picked.
 */
object OptionRules {
    /**
     * The groups the dialog shows for [dish], in its order: not archived,
     * with their live choices, and none left empty — as the site's
     * MenuService drops empty groups.
     */
    fun groups(dish: MenuDish, all: List<OptionGroup>): List<OptionGroup> {
        val byId = all.associateBy { it.id }
        return dish.groupIds.distinct().mapNotNull { byId[it] }
            .filter { !it.archived }
            .map { it.copy(options = it.live) }
            .filter { it.options.isNotEmpty() }
    }

    /** What the dialog opens on: each single-choice group on its first choice still on sale. */
    fun preset(groups: List<OptionGroup>): Map<Long, List<Long>> =
        groups.associate { group ->
            val first = group.options.firstOrNull { it.available }
            group.id to if (group.selection == Selection.SINGLE && first != null) listOf(first.id) else emptyList()
        }

    /** A tap on [option]: the one choice of a single group, on or off in a multiple one. Sold out: nothing. */
    fun toggle(group: OptionGroup, chosen: Map<Long, List<Long>>, option: MenuOption): Map<Long, List<Long>> {
        if (!option.available || group.options.none { it.id == option.id }) return chosen
        val current = chosen[group.id].orEmpty()
        val next =
            when {
                group.selection == Selection.SINGLE -> listOf(option.id)
                option.id in current -> current - option.id
                else -> current + option.id
            }
        return chosen + (group.id to next)
    }

    /** The groups whose choice is not one the database would take: a single group without exactly one, or a sold-out choice. */
    fun invalid(groups: List<OptionGroup>, chosen: Map<Long, List<Long>>): List<OptionGroup> =
        groups.filter { group ->
            val ids = chosen[group.id].orEmpty()
            val picked = group.options.filter { it.id in ids }
            picked.size != ids.distinct().size ||
                picked.any { !it.available } ||
                (group.selection == Selection.SINGLE && picked.size != 1)
        }

    /** The chosen options, in the dialog's order: group by group, each in its own order. */
    fun chosen(groups: List<OptionGroup>, chosen: Map<Long, List<Long>>): List<MenuOption> =
        groups.flatMap { group -> group.options.filter { it.id in chosen[group.id].orEmpty() } }

    /**
     * Whether [dish] can be ordered at all: on sale, and every single-choice
     * group with a choice left — no meat left means no Döner, as the
     * database's menu_item_stock has it.
     */
    fun orderable(dish: MenuDish, groups: List<OptionGroup>): Boolean =
        dish.available && !dish.archived &&
            groups.none { group -> group.selection == Selection.SINGLE && group.options.none { it.available } }
}

/** The menu as "Neue Bestellung" offers it: what is on sale, its ingredients included. */
data class OrderMenu(val dishes: List<MenuDish>, val groups: List<OptionGroup>)

/** What "Neue Bestellung" sends and how it reads the answer — staff_place_order's rules. */
object PhoneOrders {
    /** The longest dish note place_order keeps (#84). */
    const val NOTE_MAX = 80

    const val MAX_QTY = 99

    /** What staff may promise for an order for now, as on an acceptance. */
    const val ETA_MIN = 1
    const val ETA_MAX = 180

    /** How far ahead a time may be. */
    val MAX_AHEAD: Duration = Duration.ofDays(7)

    const val DEFAULT_CITY = "Leipzig"

    /** The server update that brought staff_place_order. */
    const val MIGRATION = "20261004040000_phone_orders"

    private val POSTCODE = Regex("^\\d{5}$")

    /** What still stops the order being priced or saved, in the form's order. */
    fun problems(draft: PhoneOrderDraft, now: Instant): List<DraftProblem> = buildList {
        if (draft.lines.isEmpty()) add(DraftProblem.EMPTY)
        if (draft.fulfilment.needsContact) {
            if (draft.name.isBlank()) add(DraftProblem.NAME)
            if (draft.phone.isBlank()) add(DraftProblem.PHONE)
        }
        if (draft.delivery) {
            if (draft.street.isBlank()) add(DraftProblem.STREET)
            if (!POSTCODE.matches(draft.postalCode.trim())) add(DraftProblem.POSTAL_CODE)
            if (draft.city.isBlank()) add(DraftProblem.CITY)
        }
        draft.scheduledFor?.let { if (!it.isAfter(now) || it.isAfter(now.plus(MAX_AHEAD))) add(DraftProblem.TIME) }
    }

    /** Whether the address is all there, so check-address is worth asking. */
    fun addressComplete(draft: PhoneOrderDraft): Boolean =
        draft.street.isNotBlank() && draft.city.isNotBlank() && POSTCODE.matches(draft.postalCode.trim())

    /**
     * staff_place_order's arguments. [etaMinutes] is sent for an order for
     * now only, and may be left out of a dry run; [expectedTotal] is what
     * the tablet showed, so a price that changed meanwhile is refused
     * (HB409) rather than charged.
     */
    fun body(draft: PhoneOrderDraft, etaMinutes: Int?, expectedTotal: Double?, dryRun: Boolean): JsonObject = buildJsonObject {
        put("p_source", draft.source.wire)
        put("p_fulfilment", draft.fulfilment.wire)
        put(
            "p_items",
            buildJsonArray {
                for (line in draft.lines) {
                    add(buildJsonObject {
                        put("id", line.dish.id)
                        put("qty", line.qty)
                        put("options", buildJsonArray { line.options.forEach { add(JsonPrimitive(it.id)) } })
                        line.note.trim().take(NOTE_MAX).takeIf { it.isNotEmpty() }?.let { put("note", it) }
                    })
                }
            },
        )
        putText("p_customer_name", draft.name)
        putText("p_phone", draft.phone)
        // Delivery only; the database drops them otherwise, and so does this.
        if (draft.delivery) {
            putText("p_street", draft.street)
            putText("p_postal_code", draft.postalCode)
            putText("p_city", draft.city)
            putText("p_address_note", draft.addressNote)
        }
        putText("p_notes", draft.notes)
        val at = draft.scheduledFor
        put("p_scheduled_for", at?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        if (at == null && etaMinutes != null) put("p_eta_minutes", etaMinutes)
        put("p_waive_fees", draft.waiveFees)
        if (expectedTotal != null) put("p_expected_total", expectedTotal)
        put("p_dry_run", dryRun)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putText(name: String, value: String) {
        val text = value.trim()
        put(name, if (text.isEmpty()) JsonNull else JsonPrimitive(text))
    }

    fun quote(body: String): PhoneOrderQuote = json.decodeFromString(PhoneOrderQuote.serializer(), body)

    /** staff_place_order's refusal as a [PhoneOrderException]; null for anything else. */
    fun error(e: Exception): PhoneOrderException? {
        if (e is NotAllowedException) return PhoneOrderException(PhoneOrderError.NOT_STAFF, e.message ?: "staff only")
        if (e !is BackendException) return null
        val reason = PhoneOrderError.of(e.code) ?: return null
        val minimum = if (reason == PhoneOrderError.MINIMUM) MINIMUM.find(e.message.orEmpty())?.value?.toDoubleOrNull() else null
        return PhoneOrderException(reason, e.message ?: reason.code, minimum)
    }

    /** "orders to this address start at 25.00": the figure. */
    private val MINIMUM = Regex("\\d+(?:\\.\\d+)?(?=\\s*$)")

    /**
     * The minutes to pre-select for an order for now, by the same rule as an
     * incoming one: cooking plus riding to [zone], rounded up to five, plus
     * busy mode's.
     */
    fun suggestedEta(draft: PhoneOrderDraft, zone: String?, prep: Map<Long, Int>, busyMinutes: Int): Int {
        val travel = if (draft.delivery) Eta.travelMinutes(draft.postalCode, zone) else 0
        val minutes = Eta.quote(Eta.prepMinutes(draft.lines.map { it.asItem }, prep), travel, busyMinutes)
        return minutes.coerceIn(ETA_MIN, ETA_MAX)
    }

    /** check-address's answer, read as the site reads it; anything unexpected is [AddressStatus.UNCHECKED]. */
    fun verdict(body: String): AddressCheck {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return AddressCheck(null, AddressStatus.UNCHECKED)
        val zone = obj.string("zone")
        val suggested = obj.string("postal_code")?.takeIf { POSTCODE.matches(it) }
        if (zone in KNOWN_ZONES) return AddressCheck(zone, AddressStatus.VERIFIED, suggested)
        val status = if (obj.string("status") == "unverified") AddressStatus.UNVERIFIED else AddressStatus.UNCHECKED
        return AddressCheck(null, status)
    }

    /** The rings check-address may settle on; "unknown" means "use the postcode". */
    private val KNOWN_ZONES = setOf("inner", "near", "far", "edge", "outside")
}

/** "Neue Bestellung": the menu to pick from, the address check, and staff_place_order. */
interface PhoneOrderBackend {
    /** Dishes and their choices, with what is sold out by its ingredients too. */
    suspend fun menu(): OrderMenu

    /**
     * The site's check-address, as the signed-in staff account: it files the
     * lookup under that account, where staff_place_order looks for it.
     */
    suspend fun checkAddress(street: String, postalCode: String, city: String): AddressCheck

    /**
     * Prices ([dryRun]) or saves the order. Throws [PhoneOrderException] for
     * staff_place_order's refusals and [NeedsServerUpdateException] on a
     * database without it.
     */
    suspend fun place(draft: PhoneOrderDraft, etaMinutes: Int?, expectedTotal: Double?, dryRun: Boolean): PhoneOrderQuote
}

/** [PhoneOrderBackend] over PostgREST and the edge functions, as the signed-in account. */
class SupabasePhoneOrderBackend internal constructor(private val rest: SupabaseRest) : PhoneOrderBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    private val menus = SupabaseMenuBackend(rest)

    override suspend fun menu(): OrderMenu = coroutineScope {
        val dishes = async { menus.dishes() }
        val groups = async { menus.optionGroups() }
        val dishStock = async { stock("menu_item_stock") }
        val optionStock = async { stock("menu_option_stock") }
        val items = dishStock.await()
        val options = optionStock.await()
        OrderMenu(
            dishes = dishes.await().map { dish -> items[dish.id]?.let { dish.copy(available = it) } ?: dish },
            groups =
                groups.await().map { group ->
                    group.copy(options = group.options.map { option -> options[option.id]?.let { option.copy(available = it) } ?: option })
                },
        )
    }

    /** Sold out by an ingredient as well as by its own switch; nothing on a database without the view. */
    private suspend fun stock(view: String): Map<Long, Boolean> {
        val url = rest.endpoint("rest/v1/$view").addQueryParameter("select", "id,available").build()
        return try {
            rest.call({ it.url(url).get() }) { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw rest.rejected(response.code, body, view)
                json.decodeFromString(ListSerializer(StockRow.serializer()), body).associate { it.id to it.available }
            }
        } catch (e: BackendException) {
            if (e.status == 404 || e.code == "42P01") emptyMap() else throw e
        }
    }

    override suspend fun checkAddress(street: String, postalCode: String, city: String): AddressCheck {
        val url = rest.endpoint("functions/v1/check-address").build()
        val body = buildJsonObject {
            put("street", street.trim())
            put("postal_code", postalCode.trim())
            put("city", city.trim())
        }
        return rest.call({ it.url(url).post(body.toRequestBody()) }) { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw rest.rejected(response.code, text, "check-address")
            PhoneOrders.verdict(text)
        }
    }

    override suspend fun place(draft: PhoneOrderDraft, etaMinutes: Int?, expectedTotal: Double?, dryRun: Boolean): PhoneOrderQuote {
        val body =
            try {
                rest.rpc("staff_place_order", PhoneOrders.body(draft, etaMinutes, expectedTotal, dryRun))
            } catch (e: Exception) {
                if (e is BackendException && e.missingFunction) throw NeedsServerUpdateException(PhoneOrders.MIGRATION)
                throw PhoneOrders.error(e) ?: e
            }
        return PhoneOrders.quote(body)
    }

    @Serializable private class StockRow(val id: Long, val available: Boolean)
}
