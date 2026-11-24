package de.hamzabistro.printstation.ui

import de.hamzabistro.printstation.core.AddressCheck
import de.hamzabistro.printstation.core.AddressRequest
import de.hamzabistro.printstation.core.AddressStatus
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.Fulfilment
import de.hamzabistro.printstation.core.PhoneOrderDraft
import de.hamzabistro.printstation.core.PhoneOrderQuote
import de.hamzabistro.printstation.core.RecentAddress
import de.hamzabistro.printstation.core.RequestedLink
import de.hamzabistro.printstation.core.StreetDirectory
import de.hamzabistro.printstation.core.StreetSuggestion
import java.time.Instant

/** Takes every tap and does nothing with it. */
internal open class NoOrder : OrderTaking {
    override fun loadPhoto(url: String) = Unit

    override fun loadMenu() = Unit

    override fun edit(change: (PhoneOrderDraft) -> PhoneOrderDraft) = Unit

    override fun add(line: BasketLine) = Unit

    override fun setQty(index: Int, qty: Int) = Unit

    override fun setNote(index: Int, note: String) = Unit

    override fun emptyBasket() = Unit

    override fun goTo(step: TakingStep) = Unit

    override fun pickStreet(street: StreetSuggestion) = Unit

    override fun pickAddress(address: RecentAddress) = Unit

    override fun checkAgain() = Unit

    override fun sendLink() = Unit

    override fun save() = Unit

    override fun saveWaiting() = Unit

    override fun suggestedMinutes(state: NewOrderState): Int = 35

    override fun ladder(): List<Int> = listOf(20, 30, 45, 60)

    override fun pickMinutes(minutes: Int) = Unit

    override fun dismissError() = Unit

    override fun another() = Unit
}

/** A phone order as far as it has got, with the Kasse's menu. Nobody in it exists. */
internal object OrderFixtures {
    private val lines = TillFixtures.twelve

    val called =
        NewOrderState(
            menu = TillFixtures.menu,
            bestsellers = TillFixtures.ranked.bestsellers,
            draft = PhoneOrderDraft(fulfilment = Fulfilment.DELIVERY, phone = "0176 5550001"),
            started = true,
        )

    val named = called.copy(draft = called.draft.copy(name = "Frau Schmidt-Mustermann"))

    fun at(step: TakingStep, of: NewOrderState = named) = of.copy(step = step)

    /** A street half typed, with Leipzig's like it. */
    val typing = named.copy(draft = named.draft.copy(street = "karl heine 9"), streets = StreetDirectory.matcher.match("karl heine 9"))

    private val key = listOf("Karl-Heine-Straße 9", "04229", "Leipzig")

    val addressed =
        named.copy(
            draft = named.draft.copy(street = key[0], postalCode = key[1], city = key[2], addressNote = "Hinterhaus, 3. OG, bei Mustermann klingeln"),
            address = AddressCheck("inner", AddressStatus.VERIFIED),
            checkedAddress = key,
        )

    val outside = addressed.copy(address = AddressCheck("outside", AddressStatus.VERIFIED))

    val known =
        named.copy(
            recentAddresses =
                listOf(
                    RecentAddress("Karl-Heine-Straße 9", "04229", "Leipzig", "Hinterhaus, 3. OG"),
                    RecentAddress("Lützner Straße 150", "04179", "Leipzig", ""),
                )
        )

    private val requested = RequestedLink("r-1", "0123456789abcdef0123456789abcdef")

    val texted = named.copy(link = requested)

    fun link(problem: String? = null, error: String? = null, sent: Boolean = true) =
        AddressRequest(
            id = "r-1",
            token = "0123456789abcdef0123456789abcdef",
            phone = "+491765550001",
            customerName = "Frau Schmidt-Mustermann",
            createdAt = Instant.now().minusSeconds(20),
            smsSentAt = if (sent && error == null) Instant.now().minusSeconds(10) else null,
            smsError = error,
            savedAt = Instant.now(),
            problem = problem,
        )

    fun with(count: Int, of: NewOrderState = addressed) = of.copy(draft = of.draft.copy(lines = lines.take(count)))

    /** Priced by the database: the deal off, the ring's fee on. */
    fun priced(count: Int, of: NewOrderState = addressed): NewOrderState {
        val order = with(count, of)
        val subtotal = order.draft.lines.sumOf { it.unitPrice * it.qty } - 1.0
        val quote = PhoneOrderQuote(total = subtotal + 2.5, subtotal = subtotal, deposits = 1.0, dealDiscount = 1.0, deliveryFee = 2.5, deliveryZone = "inner")
        return order.copy(quote = quote, quotedDraft = order.draft)
    }

    /** One line with a note, the first of [priced]'s. */
    fun noted(count: Int): NewOrderState {
        val order = priced(count)
        val draft = order.draft.copy(lines = order.draft.lines.mapIndexed { i, line -> if (i == 0) line.copy(note = "gut durch, Soße extra") else line })
        return order.copy(draft = draft, quotedDraft = draft)
    }

    fun saved(of: NewOrderState = priced(3)) = of.copy(step = TakingStep.DONE, saved = of.quote?.copy(id = "o-1", orderNumber = 57))

    fun waiting(of: NewOrderState = with(3, texted)) = of.copy(step = TakingStep.DONE, waiting = true)
}
