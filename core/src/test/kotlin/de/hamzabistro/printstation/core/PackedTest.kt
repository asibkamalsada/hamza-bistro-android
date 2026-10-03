package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** "Fertig": the bag is packed and waits for the driver (hamza-bistro-web#90). */
class PackedTest {
    private fun packed(id: String, at: String, confirmedAt: String = "2026-09-26T16:02:00Z", number: Long = 57) =
        order(id, confirmedAt = confirmedAt, number = number).copy(packedAt = at(at))

    // -----------------------------------------------------------------------
    // The queue
    // -----------------------------------------------------------------------

    @Test
    fun `calls packed only an accepted delivery that has not left`() {
        val bag = packed("a", "2026-09-26T16:20:00Z")
        assertTrue(StaffQueue.isPacked(bag))
        assertFalse(StaffQueue.isPacked(order("a")))
        assertFalse(StaffQueue.isPacked(bag.copy(pickup = true)))
        // packed_at is kept once it goes out: out of the door is not waiting.
        assertFalse(StaffQueue.isPacked(bag.copy(status = OrderStatus.ON_THE_WAY)))
        assertFalse(StaffQueue.isPacked(bag.copy(status = OrderStatus.DELIVERED)))
    }

    @Test
    fun `offers Fertig on an accepted delivery not packed yet, never on a pickup`() {
        assertTrue(StaffQueue.canPack(order("a")))
        assertFalse(StaffQueue.canPack(packed("a", "2026-09-26T16:20:00Z")))
        assertFalse(StaffQueue.canPack(order("a", pickup = true)))
        assertFalse(StaffQueue.canPack(order("a", status = OrderStatus.NEW, confirmedAt = null)))
        assertFalse(StaffQueue.canPack(order("a", status = OrderStatus.ON_THE_WAY)))
    }

    @Test
    fun `lists packed bags at the top of Unterwegs und abholbereit, the longest-waiting first`() {
        val waiting = order("waiting", status = OrderStatus.NEW, confirmedAt = null)
        val cooking = order("cooking", confirmedAt = "2026-09-26T15:50:00Z")
        val out = order("out", status = OrderStatus.ON_THE_WAY, confirmedAt = "2026-09-26T15:40:00Z")
        val packedLate = packed("packed-late", "2026-09-26T16:06:00Z", confirmedAt = "2026-09-26T15:45:00Z")
        val packedEarly = packed("packed-early", "2026-09-26T16:02:00Z", confirmedAt = "2026-09-26T16:00:00Z")
        val pickupMarked = packed("pickup", "2026-09-26T16:01:00Z").copy(pickup = true)

        val groups = StaffQueue.group(listOf(out, cooking, packedLate, waiting, packedEarly, pickupMarked))

        assertEquals(listOf(QueueGroup.DECIDE, QueueGroup.COOK, QueueGroup.OUT), groups.map { it.first })
        assertEquals(listOf("waiting"), groups[0].second.map { it.id })
        // A pickup is never "packed": "Abholbereit" is its ready step.
        assertEquals(listOf("cooking", "pickup"), groups[1].second.map { it.id })
        assertEquals(listOf("packed-early", "packed-late", "out"), groups[2].second.map { it.id })
    }

    @Test
    fun `puts packed first and leaves the rest as they were, for any list that has to agree`() {
        val a = order("a", confirmedAt = "2026-09-26T16:10:00Z")
        val b = order("b", confirmedAt = "2026-09-26T16:00:00Z")
        val late = packed("late", "2026-09-26T16:30:00Z")
        val early = packed("early", "2026-09-26T16:20:00Z")
        val sorted = listOf(a, late, b, early).sortedWith(StaffQueue.packedFirst)
        // Stable: a and b keep the order they came in.
        assertEquals(listOf("early", "late", "a", "b"), sorted.map { it.id })
        assertEquals(listOf("early", "late", "b", "a"), listOf(a, late, b, early).sortedWith(StaffQueue.packedFirst.then(StaffQueue.order)).map { it.id })
    }

    @Test
    fun `counts the bags waiting for the driver`() {
        val orders =
            listOf(
                packed("a", "2026-09-26T16:20:00Z"),
                packed("b", "2026-09-26T16:21:00Z"),
                packed("gone", "2026-09-26T16:10:00Z").copy(status = OrderStatus.ON_THE_WAY),
                order("c"),
            )
        assertEquals(2, StaffQueue.packedWaiting(orders))
    }

    @Test
    fun `says how long a bag has waited, rounded down, amber from five minutes and red from ten`() {
        val bag = packed("a", "2026-09-26T16:20:00Z")
        fun wait(now: String) = StaffQueue.packedWait(bag, at(now))

        assertEquals(PackedWait(0, PackedUrgency.CALM), wait("2026-09-26T16:20:59Z"))
        assertEquals(PackedWait(4, PackedUrgency.CALM), wait("2026-09-26T16:24:59Z"))
        assertEquals(PackedWait(5, PackedUrgency.SOON), wait("2026-09-26T16:25:00Z"))
        assertEquals(PackedWait(9, PackedUrgency.SOON), wait("2026-09-26T16:29:59Z"))
        assertEquals(PackedWait(10, PackedUrgency.LATE), wait("2026-09-26T16:30:00Z"))
        // A device clock a little behind the database's: not "seit -1 Min.".
        assertEquals(PackedWait(0, PackedUrgency.CALM), wait("2026-09-26T16:19:30Z"))
        assertNull(StaffQueue.packedWait(order("a"), at("2026-09-26T16:30:00Z")))
        assertNull(StaffQueue.packedWait(bag.copy(status = OrderStatus.ON_THE_WAY), at("2026-09-26T16:30:00Z")))
    }

    @Test
    fun `reads packed_at, and asks for it`() {
        val base = """{"id":"a","order_number":1,"created_at":"2026-09-26T16:00:00+00:00","status":"confirmed""""
        assertNull(json.decodeFromString(StaffOrder.serializer(), "$base}").packedAt)
        assertEquals(
            at("2026-09-26T16:20:00.123456Z"),
            json.decodeFromString(StaffOrder.serializer(), "$base,\"packed_at\":\"2026-09-26T16:20:00.123456+00:00\"}").packedAt,
        )
        assertTrue("packed_at" in StaffOrder.COLUMNS.split(","))
    }

    // -----------------------------------------------------------------------
    // "Bestellung fertig": the chime
    // -----------------------------------------------------------------------

    private val policy = AlarmPolicy()
    private val evening = at("2026-09-26T16:20:00Z")
    private val driver = AlarmSettings(packed = true)

    private fun decide(orders: List<StaffOrder>, now: Instant, settings: AlarmSettings = driver) =
        policy.decide(orders, false, null, settings, now) { 25 }.chimes.filterIsInstance<Chime.Packed>()

    @Test
    fun `follows what the device is for until somebody chooses`() {
        assertEquals(true, AlarmSettings().forDevice(driver = true).packed)
        assertEquals(false, AlarmSettings().forDevice(driver = false).packed)
        // Chosen on this device: kept, whatever it is for.
        assertEquals(false, AlarmSettings(packed = false).forDevice(driver = true).packed)
        assertEquals(true, AlarmSettings(packed = true).forDevice(driver = false).packed)
    }

    @Test
    fun `chimes once for a bag packed while this device listens, with how many wait`() {
        val cooking = order("a", number = 57)
        val other = packed("b", "2026-09-26T16:15:00Z", number = 56)
        // The starting picture: b was packed before this device looked.
        assertTrue(decide(listOf(cooking, other), evening).isEmpty())

        val bag = cooking.copy(packedAt = evening.plusSeconds(3))
        val chimes = decide(listOf(bag, other), evening.plusSeconds(5))
        assertEquals(1, chimes.size)
        assertEquals("a", chimes.single().order.id)
        assertEquals(2, chimes.single().waiting)

        // Every look after: already said.
        assertTrue(decide(listOf(bag, other), evening.plusSeconds(10)).isEmpty())
        assertTrue(decide(listOf(bag, other), evening.plusSeconds(600)).isEmpty())
    }

    @Test
    fun `does not chime again for a bag unpacked and packed again within the window`() {
        val cooking = order("a")
        decide(listOf(cooking), evening)
        val bag = cooking.copy(packedAt = evening.plusSeconds(4))
        assertEquals(1, decide(listOf(bag), evening.plusSeconds(5)).size)

        // "Doch nicht fertig" and "Fertig" again a minute on: one bag.
        decide(listOf(cooking), evening.plusSeconds(30))
        assertTrue(decide(listOf(bag.copy(packedAt = evening.plusSeconds(90))), evening.plusSeconds(95)).isEmpty())

        // Unpacked for longer than the window, then packed: a new "Fertig".
        decide(listOf(cooking), evening.plusSeconds(100))
        decide(listOf(cooking), evening.plusSeconds(400))
        assertEquals(1, decide(listOf(bag.copy(packedAt = evening.plusSeconds(500))), evening.plusSeconds(505)).size)
    }

    @Test
    fun `does not chime for a bag hidden a moment while a step on it is on its way`() {
        val bag = packed("a", "2026-09-26T16:19:00Z")
        decide(listOf(order("a")), evening)
        assertEquals(1, decide(listOf(bag), evening.plusSeconds(5)).size)
        // "+10 Min." on its undo window here: the controller leaves it out.
        decide(emptyList(), evening.plusSeconds(10))
        decide(emptyList(), evening.plusSeconds(20))
        assertTrue(decide(listOf(bag), evening.plusSeconds(30)).isEmpty())
    }

    @Test
    fun `takes in what is packed without a word after the shift was off`() {
        decide(listOf(order("a")), evening)
        // Off shift for ten minutes: nobody asked.
        val later = evening.plusSeconds(600)
        assertTrue(decide(listOf(packed("a", "2026-09-26T16:25:00Z")), later).isEmpty())
        assertEquals(1, decide(listOf(packed("a", "2026-09-26T16:25:00Z"), packed("b", "2026-09-26T16:30:05Z")), later.plusSeconds(5)).size)
    }

    @Test
    fun `is silent on a device that does not want it, and stays silent when it is switched on`() {
        decide(listOf(order("a")), evening, AlarmSettings(packed = false))
        val bag = packed("a", "2026-09-26T16:20:03Z")
        assertTrue(decide(listOf(bag), evening.plusSeconds(5), AlarmSettings(packed = false)).isEmpty())
        // The kitchen tablet, as it comes: nobody chose, and it is no driver's phone.
        assertTrue(decide(listOf(bag), evening.plusSeconds(10), AlarmSettings().forDevice(driver = false)).isEmpty())
        // Switched on now: what was already packed is not news.
        assertTrue(decide(listOf(bag), evening.plusSeconds(15)).isEmpty())
    }

    @Test
    fun `does not chime for a pickup or an order already out of the door`() {
        decide(listOf(order("a", pickup = true), order("b")), evening)
        val chimes =
            decide(
                listOf(
                    packed("a", "2026-09-26T16:20:03Z").copy(pickup = true),
                    packed("b", "2026-09-26T16:20:03Z").copy(status = OrderStatus.ON_THE_WAY),
                ),
                evening.plusSeconds(5),
            )
        assertTrue(chimes.isEmpty())
    }

    @Test
    fun `chimes in the night window too, which the controller makes silent`() {
        val night = at("2026-09-26T21:00:00Z")
        policy.decide(listOf(order("a")), false, null, driver, night) { 25 }
        val decision = policy.decide(listOf(packed("a", "2026-09-26T21:00:03Z")), false, null, driver, night.plusSeconds(5)) { 25 }
        assertTrue(decision.quiet)
        assertIs<Chime.Packed>(decision.chimes.single())
    }

    // -----------------------------------------------------------------------
    // What goes to the database
    // -----------------------------------------------------------------------

    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseStaffBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    @Test
    fun `builds the bodies of order_packed and order_unpacked`() {
        assertEquals("order_packed", Packing.function(OrderStep.Pack))
        assertEquals("order_unpacked", Packing.function(OrderStep.Unpack))
        assertEquals("""{"p_order_id":"a"}""", Packing.body(order("a")).toString())
        assertFailsWith<IllegalArgumentException> { Packing.function(OrderStep.Out) }
        assertNull(OrderStep.Pack.to)
        assertNull(OrderStep.Unpack.to)
    }

    @Test
    fun `packs and unpacks by the database's functions, with nothing but the order`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val answer = """{"id":"a","order_number":57,"packed_at":"2026-09-26T16:20:00+00:00","packed_by":"user-1"}"""
        test.reply(200, answer)
        test.reply(200, """{"id":"a","order_number":57,"packed_at":null,"packed_by":null}""")

        backend.move(order("a"), OrderStep.Pack)
        backend.move(packed("a", "2026-09-26T16:20:00Z"), OrderStep.Unpack)

        test.server.takeRequest()
        val pack = test.server.takeRequest()
        assertEquals("POST", pack.method)
        assertEquals("/rest/v1/rpc/order_packed", pack.url.encodedPath)
        assertEquals("""{"p_order_id":"a"}""", pack.body!!.utf8())
        val unpack = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/order_unpacked", unpack.url.encodedPath)
        assertEquals("""{"p_order_id":"a"}""", unpack.body!!.utf8())
    }

    @Test
    fun `says so when it is no accepted delivery any more, or the database has no Fertig yet`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB458","message":"order 57 is on_the_way: only an accepted delivery can be packed"}""")
        test.reply(404, """{"code":"P0002","message":"no such order"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.order_packed(p_order_id)"}""")
        test.reply(500, """{"code":"XX000","message":"boom"}""")

        assertFailsWith<NotPackableException> { backend.move(order("a"), OrderStep.Pack) }
        assertFailsWith<NotPackableException> { backend.move(order("a"), OrderStep.Unpack) }
        assertFailsWith<PackingUnavailableException> { backend.move(order("a"), OrderStep.Pack) }
        assertFailsWith<BackendException> { backend.move(order("a"), OrderStep.Pack) }
    }
}
