package de.hamzabistro.printstation.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient

/**
 * An S-POS phone card payments are taken on (20261128100000_card_terminals.sql
 * in hamza-bistro-web). Every one is its own Sparkasse merchant contract
 * with its own payout, so a card payment says which phone took it and the
 * Kassensturz adds the card money up per phone. The list is the shop's,
 * edited on the site's settings page; a phone that leaves is switched off,
 * never deleted.
 */
@Serializable
data class CardTerminal(
    val id: Int,
    val name: String,
    /** The Terminal-ID on the Sparkasse statement, when entered. */
    val tid: String? = null,
    /** Offered with "Karte". Off: no longer offered, still named where it was used. */
    val active: Boolean = true,
    val position: Int = 0,
)

/** What "Karte" does on this device, given the list and the phone it remembers. */
sealed interface CardChoice {
    /** No list (or none in use): a card payment with no phone, as before. */
    data object NoList : CardChoice

    /** This device's usual phone, in one tap. */
    data class Usual(val terminal: CardTerminal, val others: List<CardTerminal>) : CardChoice

    /** No usual phone (or it is switched off): pick one. */
    data class Ask(val terminals: List<CardTerminal>) : CardChoice
}

/** The S-POS phones as "Karte" offers them — payByCard() of the site's /orders. */
object CardTerminals {
    /** The server update that brought the list. */
    const val MIGRATION = "20261128100000_card_terminals"

    /** The phones "Karte" offers: the ones in use, in the list's order. */
    fun offered(terminals: List<CardTerminal>): List<CardTerminal> =
        terminals.filter { it.active }.sortedWith(compareBy<CardTerminal> { it.position }.thenBy { it.name.lowercase() })

    /**
     * What "Karte" does: one tap on [remembered] while it is in use, a pick
     * among the phones in use otherwise, and with none in use no question
     * at all.
     */
    fun choice(terminals: List<CardTerminal>, remembered: Int?): CardChoice {
        val offered = offered(terminals)
        if (offered.isEmpty()) return CardChoice.NoList
        val usual = offered.firstOrNull { it.id == remembered } ?: return CardChoice.Ask(offered)
        return CardChoice.Usual(usual, offered - usual)
    }

    /** A phone's name by its id, switched-off ones included; null when unknown. */
    fun name(terminals: List<CardTerminal>, id: Int?): String? = id?.let { wanted -> terminals.firstOrNull { it.id == wanted }?.name }

    /** "Karte · S-POS 2" for the usual phone; "Karte" when there is none. */
    fun cardLabel(card: String, choice: CardChoice): String =
        when (choice) {
            is CardChoice.Usual -> "$card · ${choice.terminal.name}"
            else -> card
        }
}

/** The list of S-POS phones, as the signed-in staff account reads it. */
interface CardTerminalBackend {
    /** Every phone, switched-off ones included; empty on a database without the list. */
    suspend fun list(): List<CardTerminal>
}

class SupabaseCardTerminalBackend internal constructor(private val rest: SupabaseRest) : CardTerminalBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun list(): List<CardTerminal> {
        val url =
            rest.endpoint("rest/v1/card_terminals")
                .addQueryParameter("select", "id,name,tid,active,position")
                .addQueryParameter("order", "position.asc,name.asc")
                .build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "card_terminals")
                // A database before the list: "Karte" asks for no phone.
                if (e is BackendException && e.missingTable) return@call emptyList()
                throw e
            }
            json.decodeFromString(ListSerializer(CardTerminal.serializer()), body)
        }
    }
}

/** One phone's card money in the Kassensturz, Stornos taken off. [id] null: nobody named a phone. */
@Serializable
data class CashUpTerminal(
    val id: Int? = null,
    val name: String? = null,
    val tid: String? = null,
    val active: Boolean? = null,
    val count: Int = 0,
    val stornos: Int = 0,
    val card: Double = 0.0,
)

/** One phone's card money in the report. [id] null: nobody named a phone. */
@Serializable
data class ReportTerminal(
    @SerialName("card_terminal_id") val id: Int? = null,
    val name: String? = null,
    val tid: String? = null,
    val orders: Int = 0,
    val amount: Double = 0.0,
)
