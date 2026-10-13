package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AddressRequestsTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseAddressRequestBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val now = Instant.parse("2026-10-13T17:00:00Z")

    private val doener = MenuDish(13, "Döner", "Döner", "", 7.5, 12, 0, "", true, emptyList())
    private val halloumi = MenuOption(13, "Halloumi", 1.0, true, emptyList())
    private val cola = MenuDish(48, "Getränke", "Cola", "", 2.25, 0, 0, "", true, emptyList(), deposit = 0.25)

    private val draft =
        PhoneOrderDraft(
            fulfilment = Fulfilment.DELIVERY,
            lines = listOf(BasketLine(doener, 2, listOf(halloumi), "ohne Zwiebeln"), BasketLine(cola, 1)),
            name = "Ali Yilmaz",
            phone = "0176 5550001",
            notes = "scharf",
        )

    private fun request(
        id: String = "r-1",
        minutesAgo: Long = 1,
        createdBy: String? = "user-1",
        saved: Boolean = true,
        addressed: Boolean = true,
        placed: Boolean = false,
        cancelled: Boolean = false,
        problem: String? = null,
        sent: Boolean = false,
        error: String? = null,
        claimedSecondsAgo: Long? = null,
    ) =
        AddressRequest(
            id = id,
            token = "0123456789abcdef0123456789abcdef",
            phone = "0176 5550001",
            customerName = "Ali",
            createdAt = now.minusSeconds(minutesAgo * 60),
            createdBy = createdBy,
            smsClaimedAt = claimedSecondsAgo?.let { now.minusSeconds(it) },
            smsSentAt = if (sent) now else null,
            smsError = error,
            street = if (addressed) "Lützner Str. 9" else null,
            postalCode = if (addressed) "04177" else null,
            city = if (addressed) "Leipzig" else null,
            addressAt = if (addressed) now else null,
            draft = if (saved) AddressRequests.draft(draft) else null,
            savedAt = if (saved) now else null,
            placedAt = if (placed) now else null,
            problem = problem,
            cancelledAt = if (cancelled) now else null,
        )

    // -----------------------------------------------------------------------
    // The link and the text
    // -----------------------------------------------------------------------

    @Test
    fun `the text is one SMS, in the SMS alphabet, with the link`() {
        val text = AddressRequests.smsText("0123456789abcdef0123456789abcdef")
        assertTrue(text.length <= 160, "${text.length} characters")
        assertTrue(text.endsWith("https://www.hamzabistro.de/a/0123456789abcdef0123456789abcdef"))
        // GSM 03.38's basic set, roughly: no „“, no –, nothing that sends it as UCS-2.
        val gsm = ('A'..'Z') + ('a'..'z') + ('0'..'9') + " :/.,-!?äöüÄÖÜß".toList()
        assertEquals(emptyList(), text.filterNot { it in gsm }.toList())
    }

    @Test
    fun `saves the basket as staff_place_order takes it, and the lines as the order carries them`() {
        val saved = AddressRequests.draft(draft)
        assertEquals(setOf("p_items", "p_customer_name", "p_phone", "p_notes", "p_waive_fees", "lines"), saved.keys)
        val items = saved["p_items"]!!.jsonArray
        assertEquals(13, items[0].jsonObject["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("[13]", items[0].jsonObject["options"].toString())
        assertEquals(JsonPrimitive("ohne Zwiebeln"), items[0].jsonObject["note"])
        assertEquals(JsonPrimitive("Ali Yilmaz"), saved["p_customer_name"])
        // No address, no minutes, no price: those come when it is placed.
        assertNull(saved["p_street"])
        assertNull(saved["p_eta_minutes"])

        val read = request()
        assertEquals(listOf("Döner", "Cola"), read.lines.map { it.name })
        assertEquals(listOf("Halloumi"), read.lines[0].options)
        assertEquals(8.5, read.lines[0].price)
        assertEquals(2 * 8.5 + 2.25, read.subtotal)
    }

    // -----------------------------------------------------------------------
    // Who does what with which request
    // -----------------------------------------------------------------------

    @Test
    fun `places its own baskets once the address is there, and nothing that waits for a person`() {
        val ready = request("ready")
        val requests =
            listOf(
                ready,
                request("no-address", addressed = false),
                request("no-basket", saved = false),
                request("placed", placed = true),
                request("cancelled", cancelled = true),
                request("problem", problem = "HB426"),
                request("other-device", createdBy = "user-2"),
            )
        assertEquals(listOf("ready"), AddressRequests.toPlace(requests, "user-1").map { it.id })
        assertEquals(emptyList(), AddressRequests.toPlace(requests, null))
        // Waiting: what has a basket and is neither placed nor cancelled, problem or not.
        assertEquals(
            setOf("ready", "no-address", "problem", "other-device"),
            AddressRequests.waiting(requests).map { it.id }.toSet(),
        )
    }

    @Test
    fun `texts what is not sent, not failed, not being sent and not a quarter of an hour old`() {
        val requests =
            listOf(
                request("new"),
                request("sent", sent = true),
                request("failed", error = "RESULT_ERROR_NO_SERVICE"),
                request("being-sent", claimedSecondsAgo = 30),
                request("claim-ran-out", claimedSecondsAgo = 180),
                request("old", minutesAgo = 16),
                request("placed", placed = true),
                request("cancelled", cancelled = true),
            )
        assertEquals(listOf("new", "claim-ran-out"), AddressRequests.toText(requests, now).map { it.id })
    }

    @Test
    fun `says that no phone sends texts once one has waited unclaimed for a while`() {
        assertFalse(AddressRequests.unsent(request(minutesAgo = 0), now))
        assertTrue(AddressRequests.unsent(request(minutesAgo = 1), now))
        assertFalse(AddressRequests.unsent(request(minutesAgo = 1, claimedSecondsAgo = 50), now))
        assertFalse(AddressRequests.unsent(request(minutesAgo = 1, sent = true), now))
    }

    // -----------------------------------------------------------------------
    // The database
    // -----------------------------------------------------------------------

    @Test
    fun `asks for a link with the number and the name`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"id":"r-1","token":"0123456789abcdef0123456789abcdef","reused":true}""")

        val link = backend.request(" 0176 5550001 ", "Ali ")

        assertEquals(RequestedLink("r-1", "0123456789abcdef0123456789abcdef", reused = true), link)
        test.server.takeRequest()
        val sent = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_request_address", sent.url.encodedPath)
        assertEquals("""{"p_phone":"0176 5550001","p_name":"Ali"}""", sent.body!!.utf8())
    }

    @Test
    fun `reads the last day's requests, and none on a database without them`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"id":"r-1","token":"0123456789abcdef0123456789abcdef","phone":"0176 5550001","customer_name":"Ali",
                 "created_at":"2026-10-13T16:59:00.123456+00:00","created_by":"user-1","sms_sent_at":"2026-10-13T16:59:05+00:00",
                 "street":"Lützner Str. 9","postal_code":"04177","city":"Leipzig","address_note":"","address_at":"2026-10-13T17:00:00+00:00",
                 "draft":null,"saved_at":null,"order_id":null,"order_number":null,"placed_at":null,"problem":null,"cancelled_at":null,
                 "sms_claimed_by":"user-2"}]""",
        )
        test.reply(404, """{"code":"PGRST205","message":"Could not find the table 'public.address_requests'"}""")

        val read = backend.since(Instant.parse("2026-10-12T17:00:00Z"))!!
        assertEquals(SmsState.SENT, read[0].sms)
        assertEquals(RecentAddress("Lützner Str. 9", "04177", "Leipzig", ""), read[0].address)
        assertFalse(read[0].waiting)
        test.server.takeRequest()
        val sent = test.server.takeRequest()
        assertEquals("/rest/v1/address_requests", sent.url.encodedPath)
        assertEquals("gte.2026-10-12T17:00:00Z", sent.url.queryParameter("created_at"))
        assertEquals("created_at.desc", sent.url.queryParameter("order"))

        assertNull(backend.since(Instant.parse("2026-10-12T17:00:00Z")))
    }

    @Test
    fun `claims a text, or learns it is not this device's to send`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"phone":"0176 5550001","token":"0123456789abcdef0123456789abcdef"}""")
        test.reply(200, "null")

        assertEquals(SmsJob("0176 5550001", "0123456789abcdef0123456789abcdef"), backend.claimSms("r-1"))
        assertNull(backend.claimSms("r-1"))
    }

    @Test
    fun `reads placing's refusals, the request's own and staff_place_order's`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB485","message":"the customer has not typed an address yet"}""")
        test.reply(400, """{"code":"HB426","message":"address outside the delivery area"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.staff_place_address_request"}""")

        assertEquals(AddressRequestError.NO_ADDRESS, assertFailsWith<AddressRequestException> { backend.place("r-1", 30, null, true) }.reason)
        assertEquals(PhoneOrderError.OUTSIDE, assertFailsWith<PhoneOrderException> { backend.place("r-1", 30, null, true) }.reason)
        assertEquals(AddressRequests.MIGRATION, assertFailsWith<NeedsServerUpdateException> { backend.place("r-1", 30, null, true) }.migration)
    }

    // -----------------------------------------------------------------------
    // Placing, once the address is there
    // -----------------------------------------------------------------------

    private class FakeRequests(var answers: MutableList<() -> PhoneOrderQuote>) : AddressRequestBackend {
        val placed = mutableListOf<Triple<Int?, Double?, Boolean>>()
        val problems = mutableListOf<String?>()
        val claimed = mutableListOf<String>()
        val results = mutableListOf<Pair<String, String?>>()
        var job: SmsJob? = SmsJob("0176 5550001", "0123456789abcdef0123456789abcdef")

        override suspend fun request(phone: String, name: String) = error("not here")

        override suspend fun resend(id: String) = error("not here")

        override suspend fun since(since: Instant): List<AddressRequest>? = error("not here")

        override suspend fun claimSms(id: String): SmsJob? = job.also { claimed += id }

        override suspend fun smsResult(id: String, error: String?) {
            results += id to error
        }

        override suspend fun save(id: String, draft: PhoneOrderDraft) = error("not here")

        override suspend fun place(id: String, etaMinutes: Int?, expectedTotal: Double?, dryRun: Boolean): PhoneOrderQuote {
            placed += Triple(etaMinutes, expectedTotal, dryRun)
            return answers.removeAt(0)()
        }

        override suspend fun noteProblem(id: String, problem: String?) {
            problems += problem
        }

        override suspend fun close(id: String, orderId: String) = error("not here")

        override suspend fun cancel(id: String) = error("not here")
    }

    private class FakeOrders(val check: () -> AddressCheck) : PhoneOrderBackend {
        val checked = mutableListOf<List<String>>()

        override suspend fun menu() = error("not here")

        override suspend fun checkAddress(street: String, postalCode: String, city: String): AddressCheck {
            checked += listOf(street, postalCode, city)
            return check()
        }

        override suspend fun place(draft: PhoneOrderDraft, etaMinutes: Int?, expectedTotal: Double?, dryRun: Boolean) = error("not here")
    }

    private object Quiet : Logger {
        override fun info(message: String) = Unit

        override fun warn(message: String, error: Throwable?) = Unit
    }

    private var clock = now

    private fun placer(requests: FakeRequests, orders: FakeOrders) =
        AddressRequestPlacer(requests, orders, Quiet, minutes = { items, postalCode, zone -> if (zone == "inner" && postalCode == "04177" && items.size == 2) 35 else 99 }, now = { clock })

    private val inner = { AddressCheck("inner", AddressStatus.VERIFIED) }

    @Test
    fun `checks the address as staff, prices it, and saves it at that price`() = runBlocking<Unit> {
        val requests = FakeRequests(mutableListOf({ PhoneOrderQuote(total = 19.25, dryRun = true) }, { PhoneOrderQuote(id = "o-1", orderNumber = 123, total = 19.25) }))
        val orders = FakeOrders(inner)

        assertTrue(placer(requests, orders).place(request()))

        assertEquals(listOf(listOf("Lützner Str. 9", "04177", "Leipzig")), orders.checked)
        assertEquals(listOf<Triple<Int?, Double?, Boolean>>(Triple(35, null, true), Triple(35, 19.25, false)), requests.placed)
        assertTrue(requests.problems.isEmpty())
    }

    @Test
    fun `an address outside the area waits for a person, and is not priced`() = runBlocking<Unit> {
        val requests = FakeRequests(mutableListOf())
        val orders = FakeOrders { AddressCheck("outside", AddressStatus.VERIFIED) }

        assertFalse(placer(requests, orders).place(request()))

        assertEquals(listOf<String?>("HB426"), requests.problems)
        assertTrue(requests.placed.isEmpty())
    }

    @Test
    fun `what the database refuses is noted by its code, a dropped line is tried again later and not noted`() = runBlocking<Unit> {
        val minimum = FakeRequests(mutableListOf({ throw PhoneOrderException(PhoneOrderError.MINIMUM, "minimum 25.00", 25.0) }))
        assertFalse(placer(minimum, FakeOrders(inner)).place(request()))
        assertEquals(listOf<String?>("HB427"), minimum.problems)

        val offline = FakeRequests(mutableListOf({ throw java.io.IOException("offline") }, { PhoneOrderQuote(total = 19.25, dryRun = true) }, { PhoneOrderQuote(id = "o-1", orderNumber = 5, total = 19.25) }))
        val placer = placer(offline, FakeOrders(inner))
        val read = AddressRequestsState(available = true, requests = listOf(request()))
        placer.placeAll(read, "user-1")
        assertTrue(offline.problems.isEmpty())
        // Not straight away...
        clock = now.plusSeconds(10)
        placer.placeAll(read, "user-1")
        assertEquals(1, offline.placed.size)
        // ...but after a while.
        clock = now.plusSeconds(31)
        placer.placeAll(read, "user-1")
        assertEquals(3, offline.placed.size)
        // Placed: not again, until it is read back as placed.
        placer.placeAll(read, "user-1")
        assertEquals(3, offline.placed.size)
    }

    @Test
    fun `placed by another device first, nothing more`() = runBlocking<Unit> {
        val requests = FakeRequests(mutableListOf({ PhoneOrderQuote(id = "o-1", orderNumber = 123, already = true, dryRun = true) }))

        assertTrue(placer(requests, FakeOrders(inner)).place(request()))
        assertEquals(1, requests.placed.size)
    }

    @Test
    fun `a price that moved between dry run and save is priced once more`() = runBlocking<Unit> {
        val requests =
            FakeRequests(
                mutableListOf(
                    { PhoneOrderQuote(total = 19.25, dryRun = true) },
                    { throw PhoneOrderException(PhoneOrderError.TOTAL_CHANGED, "total changed") },
                    { PhoneOrderQuote(total = 18.25, dryRun = true) },
                    { PhoneOrderQuote(id = "o-1", orderNumber = 7, total = 18.25) },
                )
            )

        assertTrue(placer(requests, FakeOrders(inner)).place(request()))
        assertEquals(Triple<Int?, Double?, Boolean>(35, 18.25, false), requests.placed.last())
    }

    // -----------------------------------------------------------------------
    // Texting, wherever a transport runs
    // -----------------------------------------------------------------------

    @Test
    fun `claims each text, sends it with the link, and says how it went`() = runBlocking<Unit> {
        val requests = FakeRequests(mutableListOf())
        val sent = mutableListOf<Pair<String, String>>()
        var answer: String? = null
        val sender =
            AddressSmsSender(
                requests,
                { phone, text ->
                    sent += phone to text
                    answer
                },
                Quiet,
                now = { now },
            )
        val read = AddressRequestsState(available = true, requests = listOf(request("r-1"), request("r-2", sent = true)))

        sender.sendAll(read)
        assertEquals(listOf("r-1"), requests.claimed)
        assertEquals(listOf("0176 5550001" to AddressRequests.smsText("0123456789abcdef0123456789abcdef")), sent)
        assertEquals(listOf<Pair<String, String?>>("r-1" to null), requests.results)

        answer = "RESULT_ERROR_NO_SERVICE"
        sender.sendAll(read)
        assertEquals("r-1" to "RESULT_ERROR_NO_SERVICE", requests.results.last())

        // Claimed elsewhere: not sent here.
        requests.job = null
        sender.sendAll(read)
        assertEquals(2, sent.size)
    }
}
