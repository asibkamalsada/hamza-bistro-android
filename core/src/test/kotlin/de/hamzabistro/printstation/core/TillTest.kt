package de.hamzabistro.printstation.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The till as the database keeps it: what counter_sale_*() answers, and how it refuses. */
private class FakeTill : CounterBackend {
    var enabled = true
    var exists = true
    val sales = linkedMapOf<String, CounterSale>()

    /** Every call, as "open s-1", "pay s-1 card". */
    val calls = mutableListOf<String>()

    /** The next call fails with this — before anything happens, or after ([lost]). */
    var fail: Exception? = null
    var lost = false

    /** The next call waits for this. */
    var gate: CompletableDeferred<Unit>? = null

    /** The receipts of paid sales; the last one stays. */
    var receipts = listOf(CounterReceipt("t", "pending"))
    private var number = 100L

    private suspend fun <T> call(what: String, block: () -> T): T {
        calls += what
        gate?.let {
            gate = null
            it.await()
        }
        val failure = fail
        fail = null
        if (failure != null && !lost) throw failure
        val answer = block()
        if (failure != null) {
            lost = false
            throw failure
        }
        return answer
    }

    private fun refuse(reason: CounterError): Nothing = throw CounterException(reason, reason.code)

    private fun sale(id: String) = sales[id] ?: refuse(CounterError.GONE)

    override suspend fun status(): CounterStatus? = call("status") {
        if (!exists) null else CounterStatus(enabled, sales.values.count { it.stateName == "open" }, sales.values.count { it.stale })
    }

    override suspend fun price(lines: List<BasketLine>): CounterPrice = call("price") { CounterPrice(CounterSales.euros(CounterSales.subtotal(lines)) - 0.5, 0.5) }

    override suspend fun open(id: String, lines: List<BasketLine>, dineIn: Boolean, training: Boolean, expectedTotal: Double?): CounterSale = call("open $id") {
        if (!training && !enabled) refuse(CounterError.OFF)
        if (!training && sales.values.any { it.stale && it.stateName == "open" && it.id != id }) refuse(CounterError.STALE)
        val total = CounterSales.euros(CounterSales.subtotal(lines)) - 0.5
        if (expectedTotal != null && expectedTotal != total) refuse(CounterError.TOTAL_CHANGED)
        sales.getOrPut(id) { CounterSale(id, number++, "open", training, dineIn, total = total) }
    }

    override suspend fun cardPending(id: String, pending: Boolean): CounterSale = call("card $id $pending") {
        sale(id).copy(pendingMethod = if (pending) "card" else null).also { sales[id] = it }
    }

    override suspend fun pay(id: String, method: CounterPayment): CounterSale = call("pay $id ${method.wire}") {
        val sale = sale(id)
        if (sale.stateName == "paid" && sale.paymentMethod == method.wire) return@call sale
        if (sale.stateName != "open") refuse(CounterError.ALREADY)
        sale.copy(stateName = "paid", paymentMethod = method.wire, pendingMethod = null, orderId = "o-$id", receipt = receipts.first()).also { sales[id] = it }
    }

    override suspend fun abort(id: String): CounterSale = call("abort $id") {
        val sale = sale(id)
        if (sale.stateName == "paid") refuse(CounterError.ALREADY)
        sale.copy(stateName = "aborted", pendingMethod = null).also { sales[id] = it }
    }

    override suspend fun get(id: String): CounterSale = call("get $id") {
        val sale = sale(id)
        if (sale.stateName != "paid") return@call sale
        receipts = receipts.drop(1).ifEmpty { receipts }
        sale.copy(receipt = receipts.first()).also { sales[id] = it }
    }

    override suspend fun openSales(): List<CounterSale> = call("list") { sales.values.filter { it.stateName == "open" } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TillTest {
    private val fake = FakeTill()
    private var ids = 0
    private val till = Till(fake, newId = { "s-${++ids}" })
    private val now
        get() = till.state.value

    private val doener = MenuDish(13, "Döner", "Döner", "", 7.5, 12, 0, "", true, emptyList())
    private val cola = MenuDish(48, "Getränke", "Cola", "", 2.5, 0, 0, "", true, emptyList(), deposit = 0.25)

    private suspend fun rungUp(dineIn: Boolean = false) {
        till.refresh()
        till.add(BasketLine(doener, 2))
        till.setDineIn(dineIn)
        till.reprice()
        till.ringUp()
    }

    // ---------------------------------------------------------------------
    // The basket
    // ---------------------------------------------------------------------

    @Test
    fun `the running total is the menu's until the database has answered for this basket`() = runTest {
        till.refresh()
        till.add(BasketLine(doener, 1))
        assertEquals(750, now.totalCents)
        till.reprice()
        assertEquals(700, now.totalCents)
        assertTrue(now.priced)
        till.add(BasketLine(cola, 1))
        assertFalse(now.priced)
        assertEquals(1000, now.totalCents)
        // Nothing has been sent anywhere but for the price.
        assertEquals(listOf("status", "price"), fake.calls)
    }

    @Test
    fun `a price that comes late for another basket is not shown`() = runTest {
        till.add(BasketLine(doener, 1))
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val pricing = async { till.reprice() }
        runCurrent()
        till.add(BasketLine(cola, 1))
        gate.complete(Unit)
        pricing.await()
        assertNull(now.price)
    }

    // ---------------------------------------------------------------------
    // Cash
    // ---------------------------------------------------------------------

    @Test
    fun `rings up, takes the cash, and gives the receipt`() = runTest {
        rungUp(dineIn = true)
        assertEquals(TillStep.PAY, now.step)
        assertEquals(1450, CounterSales.cents(now.sale!!.total))
        assertTrue(now.sale!!.dineIn)

        till.chooseCash()
        assertEquals(TillStep.CASH, now.step)
        assertTrue(now.cashReady, "nothing typed: the exact amount")
        till.setGiven("10")
        assertFalse(now.cashReady)
        till.cashPaid()
        assertEquals(TillStep.CASH, now.step, "not enough was given")
        till.setGiven("20")
        assertEquals(550L, now.changeCents)
        till.cashPaid()
        assertEquals(TillStep.DONE, now.step)
        assertEquals(CounterPayment.CASH, now.sale!!.payment)
        assertEquals("o-s-1", now.sale!!.orderId)

        till.next()
        assertEquals(TillStep.DISHES, now.step)
        assertTrue(now.lines.isEmpty() && now.sale == null && !now.dineIn)
        assertEquals(listOf("open s-1", "pay s-1 cash"), fake.calls.filter { it.startsWith("open") || it.startsWith("pay") })
    }

    @Test
    fun `a double tap rings up once, pays once`() = runTest {
        till.add(BasketLine(doener, 1))
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val first = async { till.ringUp() }
        runCurrent()
        assertTrue(now.busy)
        till.ringUp()
        // The basket is closed while it is being rung up.
        till.add(BasketLine(cola, 1))
        gate.complete(Unit)
        first.await()
        assertEquals(listOf("open s-1"), fake.calls)
        assertEquals(1, now.lines.size)

        till.chooseCash()
        val paying = CompletableDeferred<Unit>()
        fake.gate = paying
        val pay = async { till.cashPaid() }
        runCurrent()
        till.cashPaid()
        till.abort()
        paying.complete(Unit)
        pay.await()
        assertEquals(listOf("open s-1", "pay s-1 cash"), fake.calls)
    }

    // ---------------------------------------------------------------------
    // No connection
    // ---------------------------------------------------------------------

    @Test
    fun `without a connection nothing is sold, and the same tap again is the same sale`() = runTest {
        till.add(BasketLine(doener, 1))
        fake.fail = IOException("no route")
        till.ringUp()
        assertEquals(TillStep.DISHES, now.step)
        assertEquals(TillProblem.Offline(unsure = true), now.problem)
        assertFalse(now.busy)

        // The answer was lost: the sale is open on the server.
        fake.fail = IOException("timeout")
        fake.lost = true
        till.ringUp()
        assertEquals(TillStep.DISHES, now.step)
        assertEquals(1, fake.sales.size)

        till.ringUp()
        assertEquals(TillStep.PAY, now.step)
        assertNull(now.problem)
        assertEquals(1, fake.sales.size, "one basket, one sale")
        assertEquals(listOf("open s-1", "open s-1", "open s-1"), fake.calls)
    }

    @Test
    fun `another basket is another sale, and the one left behind is on the list`() = runTest {
        till.add(BasketLine(doener, 1))
        fake.fail = IOException("timeout")
        fake.lost = true
        till.ringUp()
        till.add(BasketLine(cola, 1))
        till.ringUp()
        assertEquals("s-2", now.sale!!.id)
        till.refresh()
        assertEquals(listOf("s-1"), now.open.map { it.id })
    }

    @Test
    fun `a payment whose answer was lost is asked for again, not made twice`() = runTest {
        rungUp()
        till.chooseCash()
        fake.fail = IOException("timeout")
        fake.lost = true
        till.cashPaid()
        assertEquals(TillStep.CASH, now.step)
        assertEquals(TillProblem.Offline(unsure = true), now.problem)
        till.cashPaid()
        assertEquals(TillStep.DONE, now.step)
        assertEquals(1, fake.sales.values.count { it.stateName == "paid" })
    }

    @Test
    fun `paid in cash unheard, then aborted - the screen shows that it was paid`() = runTest {
        rungUp()
        till.chooseCash()
        fake.fail = IOException("timeout")
        fake.lost = true
        till.cashPaid()
        till.back()
        till.abort()
        assertEquals(TillStep.DONE, now.step)
        assertEquals(CounterPayment.CASH, now.sale!!.payment)
        assertNull(now.problem)
    }

    @Test
    fun `a server error after the work may have been done is unsure too`() = runTest {
        rungUp()
        till.chooseCash()
        fake.fail = BackendException(503, "down")
        till.cashPaid()
        assertEquals(TillProblem.Offline(unsure = true), now.problem)
        fake.fail = BackendException(400, "odd")
        till.cashPaid()
        assertIs<TillProblem.Other>(now.problem)
        fake.fail = SignedOutException()
        till.cashPaid()
        assertEquals(TillProblem.SignedOut, now.problem)
    }

    // ---------------------------------------------------------------------
    // Card
    // ---------------------------------------------------------------------

    @Test
    fun `a card is booked only once somebody says it was paid`() = runTest {
        rungUp()
        till.chooseCard()
        assertEquals(TillStep.CARD, now.step)
        assertTrue(fake.sales["s-1"]!!.cardPending)
        assertEquals("open", fake.sales["s-1"]!!.stateName)
        till.cardPaid()
        assertEquals(TillStep.DONE, now.step)
        assertEquals(CounterPayment.CARD, now.sale!!.payment)
        assertEquals(listOf("card s-1 true", "pay s-1 card"), fake.calls.takeLast(2))
    }

    @Test
    fun `a declined card goes to cash, or ends without a sale`() = runTest {
        rungUp()
        till.chooseCard()
        till.cardDeclined()
        assertEquals(TillStep.PAY, now.step)
        assertTrue(now.declined)
        assertFalse(fake.sales["s-1"]!!.cardPending)
        till.chooseCash()
        assertFalse(now.declined)
        till.cashPaid()
        assertEquals("cash", fake.sales["s-1"]!!.paymentMethod)
        till.next()

        till.add(BasketLine(cola, 1))
        till.ringUp()
        till.chooseCard()
        till.cardDeclined()
        till.abort()
        assertEquals("aborted", fake.sales["s-2"]!!.stateName)
        assertEquals(TillStep.DISHES, now.step)
        assertNull(now.sale)
        assertTrue(now.lines.isEmpty())
    }

    @Test
    fun `without a connection the amount for the card is not shown at all`() = runTest {
        rungUp()
        fake.fail = IOException("no route")
        till.chooseCard()
        assertEquals(TillStep.PAY, now.step)
        assertEquals(TillProblem.Offline(unsure = false), now.problem)
    }

    // ---------------------------------------------------------------------
    // Open sales
    // ---------------------------------------------------------------------

    @Test
    fun `an open sale is found again after a restart, and goes on where it was`() = runTest {
        rungUp()
        till.chooseCard()

        val restarted = Till(fake, newId = { "other" })
        restarted.refresh()
        val found = restarted.state.value.open.single()
        restarted.resume(found)
        assertEquals(TillStep.CARD, restarted.state.value.step, "the card may have gone through")
        assertTrue(restarted.state.value.open.isEmpty())
        restarted.cardPaid()
        assertEquals(TillStep.DONE, restarted.state.value.step)

        // This device still shows it, and learns what became of it.
        till.cardDeclined()
        till.abort()
        assertEquals(TillStep.DONE, now.step)
    }

    @Test
    fun `a sale left for later is on the list again`() = runTest {
        rungUp()
        till.park()
        assertEquals(TillStep.DISHES, now.step)
        assertEquals(listOf("s-1"), now.open.map { it.id })
        till.resume(now.open.single())
        assertEquals(TillStep.PAY, now.step)
    }

    @Test
    fun `one from yesterday stops the next, until it is decided`() = runTest {
        fake.sales["old"] = CounterSale("old", 7, "open", stale = true, total = 9.0)
        till.add(BasketLine(doener, 1))
        till.ringUp()
        assertEquals(TillProblem.Refused(CounterError.STALE), now.problem)
        assertEquals(TillStep.DISHES, now.step)
        assertEquals(listOf("old"), now.stale.map { it.id })
        assertEquals(1, now.lines.size, "the basket is kept")

        till.resume(now.stale.single())
        till.abort()
        assertTrue(now.open.isEmpty())
    }

    @Test
    fun `resuming keeps nothing of the basket's id`() = runTest {
        till.add(BasketLine(doener, 1))
        fake.fail = IOException("no route")
        till.ringUp()
        fake.sales["other"] = CounterSale("other", 8, "open", total = 5.0)
        till.refresh()
        till.resume(now.open.single())
        till.abort()
        till.add(BasketLine(cola, 1))
        till.ringUp()
        assertEquals("s-2", now.sale!!.id)
    }

    // ---------------------------------------------------------------------
    // On, off, missing
    // ---------------------------------------------------------------------

    @Test
    fun `a till that is off takes training only`() = runTest {
        fake.enabled = false
        till.refresh()
        assertEquals(true, now.available)
        assertTrue(now.off && now.training)
        till.setTraining(false)
        assertTrue(now.training)
        till.add(BasketLine(doener, 1))
        till.ringUp()
        assertTrue(now.sale!!.training)
        till.abort()
        assertTrue(now.training, "still off")
    }

    @Test
    fun `switched off meanwhile - the refusal says so and training is what is left`() = runTest {
        till.refresh()
        fake.enabled = false
        till.add(BasketLine(doener, 1))
        till.ringUp()
        assertEquals(TillProblem.Refused(CounterError.OFF), now.problem)
        assertTrue(now.off && now.training)
    }

    @Test
    fun `training ends with its sale`() = runTest {
        till.refresh()
        till.setTraining(true)
        till.add(BasketLine(doener, 1))
        till.ringUp()
        till.chooseCash()
        till.cashPaid()
        till.next()
        assertFalse(now.training)
    }

    @Test
    fun `a database without a till has none, and a failed look changes nothing known`() = runTest {
        fake.exists = false
        till.refresh()
        assertEquals(false, now.available)

        fake.exists = true
        till.refresh()
        assertEquals(true, now.available)
        fake.fail = IOException("no route")
        till.refresh()
        assertEquals(true, now.available)
        assertEquals(TillProblem.Offline(unsure = false), now.problem)
        till.refresh()
        assertNull(now.problem)
        fake.fail = NotAllowedException()
        till.refresh()
        assertEquals(TillProblem.Refused(CounterError.NOT_STAFF), now.problem)
        till.refresh()
        assertNull(now.problem)
        // What a step was refused for is not swept away by a look.
        fake.sales["old"] = CounterSale("old", 7, "open", stale = true)
        till.add(BasketLine(doener, 1))
        till.ringUp()
        till.refresh()
        assertEquals(TillProblem.Refused(CounterError.STALE), now.problem)
    }

    @Test
    fun `a changed price is refused and shown anew`() = runTest {
        till.add(BasketLine(doener, 1))
        till.reprice()
        fake.fail = CounterException(CounterError.TOTAL_CHANGED, "HB409")
        till.ringUp()
        assertEquals(TillProblem.Refused(CounterError.TOTAL_CHANGED), now.problem)
        till.reprice()
        till.ringUp()
        assertEquals(TillStep.PAY, now.step)
    }

    // ---------------------------------------------------------------------
    // The receipt
    // ---------------------------------------------------------------------

    @Test
    fun `waits for the signature, and stops waiting when the TSE does not answer`() = runTest {
        fake.receipts = listOf(CounterReceipt("t", "pending"), CounterReceipt("t", "pending"), CounterReceipt("t", "signed"))
        rungUp()
        till.chooseCash()
        till.cashPaid()
        assertTrue(now.sale!!.receipt!!.pending)
        val start = currentTime
        // One ask fails on the way: the next is made all the same.
        val waiting = async { till.awaitReceipt() }
        runCurrent()
        fake.fail = IOException("blip")
        advanceTimeBy(1_300)
        waiting.await()
        assertTrue(now.sale!!.receipt!!.signed)
        assertFalse(now.stoppedWaiting)
        assertTrue(currentTime - start < 10_000)
        till.next()

        fake.receipts = listOf(CounterReceipt("t", "pending", outage = true))
        till.add(BasketLine(cola, 1))
        till.ringUp()
        till.chooseCash()
        till.cashPaid()
        till.awaitReceipt()
        assertTrue(now.stoppedWaiting)
        assertTrue(now.sale!!.receipt!!.outage)
    }

    @Test
    fun `nothing is asked for a sale that has no receipt to wait for`() = runTest {
        rungUp()
        till.awaitReceipt()
        assertEquals(0, fake.calls.count { it.startsWith("get") })
    }
}
