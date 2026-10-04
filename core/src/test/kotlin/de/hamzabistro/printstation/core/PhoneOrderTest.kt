package de.hamzabistro.printstation.core

import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PhoneOrderTest {
    private val now = at("2026-10-04T16:00:00Z")

    private fun option(id: Long, name: String, price: Double = 0.0, available: Boolean = true, archived: Boolean = false) =
        MenuOption(id, name, price, available, emptyList(), archived = archived)

    private val meat =
        OptionGroup(
            id = 10,
            name = "Dein Fleisch",
            options = listOf(option(101, "Kalb", available = false), option(102, "Hähnchen"), option(103, "Lamm", 1.0)),
            dishes = listOf("Döner"),
            selection = Selection.SINGLE,
        )
    private val sauces =
        OptionGroup(
            id = 20,
            name = "Soßen",
            options = listOf(option(201, "Knoblauch"), option(202, "Scharf", 0.5), option(203, "Alt", archived = true)),
            dishes = listOf("Döner"),
            selection = Selection.MULTIPLE,
        )
    private val empty = OptionGroup(id = 30, name = "Leer", options = listOf(option(301, "Weg", archived = true)), dishes = listOf("Döner"))
    private val gone = OptionGroup(id = 40, name = "Alt", options = listOf(option(401, "X")), dishes = listOf("Döner"), archived = true)

    private fun dish(id: Long, name: String, price: Double, groups: List<Long> = emptyList(), available: Boolean = true, deposit: Double = 0.0) =
        MenuDish(id, "Döner", name, "", price, 8, 0, "", available, emptyList(), deposit = deposit, groupIds = groups)

    // Sauces before meat on this dish: the dish's order, not the groups' names.
    private val doener = dish(1, "Döner", 7.5, listOf(20, 30, 10, 40))
    private val cola = dish(3, "Cola", 2.25, deposit = 0.25)

    private val all = listOf(meat, sauces, empty, gone)

    // ---------------------------------------------------------------------
    // The options dialog's rules
    // ---------------------------------------------------------------------

    @Test
    fun `offers the dish's groups in its own order, live choices only, and no empty or archived group`() {
        val groups = OptionRules.groups(doener, all)
        assertEquals(listOf(20L, 10L), groups.map { it.id })
        assertEquals(listOf(201L, 202L), groups[0].options.map { it.id })
        assertEquals(listOf(101L, 102L, 103L), groups[1].options.map { it.id })
    }

    @Test
    fun `opens a single choice on its first choice still on sale, and a multiple one on none`() {
        val preset = OptionRules.preset(OptionRules.groups(doener, all))
        assertEquals(listOf(102L), preset[10])
        assertEquals(emptyList(), preset[20])
    }

    @Test
    fun `a single choice replaces, a multiple one toggles, and a sold-out choice cannot be picked`() {
        val groups = OptionRules.groups(doener, all)
        val (sauce, meatGroup) = groups
        var chosen = OptionRules.preset(groups)
        chosen = OptionRules.toggle(meatGroup, chosen, meatGroup.options[2])
        assertEquals(listOf(103L), chosen[10])
        chosen = OptionRules.toggle(meatGroup, chosen, meatGroup.options[0])
        assertEquals(listOf(103L), chosen[10], "Kalb is sold out")

        chosen = OptionRules.toggle(sauce, chosen, sauce.options[1])
        chosen = OptionRules.toggle(sauce, chosen, sauce.options[0])
        assertEquals(listOf(202L, 201L), chosen[20])
        chosen = OptionRules.toggle(sauce, chosen, sauce.options[1])
        assertEquals(listOf(201L), chosen[20])
    }

    @Test
    fun `the chosen options come out in the dialog's order, whatever order they were tapped in`() {
        val groups = OptionRules.groups(doener, all)
        val chosen = mapOf(10L to listOf(103L), 20L to listOf(202L, 201L))
        assertEquals(listOf("Knoblauch", "Scharf", "Lamm"), OptionRules.chosen(groups, chosen).map { it.name })
        assertTrue(OptionRules.invalid(groups, chosen).isEmpty())
    }

    @Test
    fun `a single choice needs exactly one choice on sale, a multiple one takes any number, none included`() {
        val groups = OptionRules.groups(doener, all)
        assertEquals(listOf(10L), OptionRules.invalid(groups, mapOf(20L to emptyList())).map { it.id })
        assertEquals(listOf(10L), OptionRules.invalid(groups, mapOf(10L to listOf(102L, 103L))).map { it.id })
        assertEquals(listOf(10L), OptionRules.invalid(groups, mapOf(10L to listOf(101L))).map { it.id }, "sold out")
        assertEquals(listOf(20L), OptionRules.invalid(groups, mapOf(10L to listOf(102L), 20L to listOf(999L))).map { it.id }, "not in it")
        assertTrue(OptionRules.invalid(groups, mapOf(10L to listOf(102L), 20L to listOf(201L, 202L))).isEmpty())
    }

    @Test
    fun `a dish is not orderable when sold out, or when a single choice has nothing left`() {
        val groups = OptionRules.groups(doener, all)
        assertTrue(OptionRules.orderable(doener, groups))
        assertFalse(OptionRules.orderable(doener.copy(available = false), groups))
        val noMeat = meat.copy(options = meat.options.map { it.copy(available = false) })
        assertFalse(OptionRules.orderable(doener, OptionRules.groups(doener, listOf(noMeat, sauces))))
        // No sauce left is still a Döner.
        val noSauce = sauces.copy(options = sauces.options.map { it.copy(available = false) })
        assertTrue(OptionRules.orderable(doener, OptionRules.groups(doener, listOf(meat, noSauce))))
    }

    // ---------------------------------------------------------------------
    // What is sent
    // ---------------------------------------------------------------------

    private val basket =
        listOf(
            BasketLine(doener, 2, listOf(option(201, "Knoblauch"), option(103, "Lamm", 1.0)), "  ohne Zwiebeln "),
            BasketLine(cola, 1),
        )

    private val pickup = PhoneOrderDraft(lines = basket, name = " Erika ", phone = "0341 1", notes = "")

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun `sends the items with their options in order, and a note only where there is one`() {
        val body = PhoneOrders.body(pickup, etaMinutes = 20, expectedTotal = null, dryRun = false)
        val items = body["p_items"]!!.jsonArray
        assertEquals(2, items.size)
        val first = items[0].jsonObject
        assertEquals(1L, first["id"]!!.jsonPrimitive.content.toLong())
        assertEquals(2, first["qty"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf(201L, 103L), first["options"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })
        assertEquals("ohne Zwiebeln", first.text("note"))
        val second = items[1].jsonObject
        assertEquals(JsonArray(emptyList()), second["options"])
        assertFalse("note" in second)
    }

    @Test
    fun `a pickup by phone, name and phone trimmed, no address, the minutes for now, saved for real`() {
        val body = PhoneOrders.body(pickup, etaMinutes = 20, expectedTotal = 25.0, dryRun = false)
        assertEquals("phone", body.text("p_source"))
        assertEquals("pickup", body.text("p_fulfilment"))
        assertEquals("Erika", body.text("p_customer_name"))
        assertEquals("0341 1", body.text("p_phone"))
        assertFalse("p_street" in body)
        assertFalse("p_postal_code" in body)
        assertEquals(JsonNull, body["p_notes"])
        assertEquals(JsonNull, body["p_scheduled_for"])
        assertEquals("20", body["p_eta_minutes"]!!.jsonPrimitive.content)
        assertEquals("false", body["p_waive_fees"]!!.jsonPrimitive.content)
        assertEquals(25.0, body["p_expected_total"]!!.jsonPrimitive.content.toDouble())
        assertEquals("false", body["p_dry_run"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a delivery sends the address and the waiver`() {
        val draft =
            pickup.copy(
                fulfilment = Fulfilment.DELIVERY,
                street = "Karl-Heine-Str. 12 ",
                postalCode = "04229",
                city = "Leipzig",
                addressNote = "Hinterhaus",
                notes = "Bitte klingeln",
                waiveFees = true,
            )
        val body = PhoneOrders.body(draft, etaMinutes = 45, expectedTotal = null, dryRun = false)
        assertEquals("delivery", body.text("p_fulfilment"))
        assertEquals("Karl-Heine-Str. 12", body.text("p_street"))
        assertEquals("04229", body.text("p_postal_code"))
        assertEquals("Leipzig", body.text("p_city"))
        assertEquals("Hinterhaus", body.text("p_address_note"))
        assertEquals("Bitte klingeln", body.text("p_notes"))
        assertEquals("true", body["p_waive_fees"]!!.jsonPrimitive.content)
        assertFalse("p_expected_total" in body)
    }

    @Test
    fun `eaten here at the counter may go without a name or phone`() {
        val draft = PhoneOrderDraft(source = OrderSource.COUNTER, fulfilment = Fulfilment.DINE_IN, lines = basket)
        val body = PhoneOrders.body(draft, etaMinutes = 10, expectedTotal = null, dryRun = false)
        assertEquals("counter", body.text("p_source"))
        assertEquals("dine_in", body.text("p_fulfilment"))
        assertEquals(JsonNull, body["p_customer_name"])
        assertEquals(JsonNull, body["p_phone"])
        assertTrue(PhoneOrders.problems(draft, now).isEmpty())
    }

    @Test
    fun `a time ahead is sent as the time, with no minutes`() {
        val draft = pickup.copy(scheduledFor = at("2026-10-05T11:30:00Z"))
        val body = PhoneOrders.body(draft, etaMinutes = 20, expectedTotal = null, dryRun = false)
        assertEquals("2026-10-05T11:30:00Z", body.text("p_scheduled_for"))
        assertFalse("p_eta_minutes" in body)
    }

    @Test
    fun `a dry run may go without minutes and says it is one`() {
        val body = PhoneOrders.body(pickup, etaMinutes = null, expectedTotal = null, dryRun = true)
        assertEquals("true", body["p_dry_run"]!!.jsonPrimitive.content)
        assertFalse("p_eta_minutes" in body)
    }

    @Test
    fun `a note longer than the database keeps is cut to it`() {
        val draft = pickup.copy(lines = listOf(BasketLine(cola, 1, note = "x".repeat(90))))
        val note = PhoneOrders.body(draft, null, null, true)["p_items"]!!.jsonArray[0].jsonObject.text("note")
        assertEquals(PhoneOrders.NOTE_MAX, note?.length)
    }

    // ---------------------------------------------------------------------
    // What is still missing
    // ---------------------------------------------------------------------

    @Test
    fun `asks for a basket, a name and a phone for a pickup`() {
        assertEquals(
            listOf(DraftProblem.EMPTY, DraftProblem.NAME, DraftProblem.PHONE),
            PhoneOrders.problems(PhoneOrderDraft(), now),
        )
        assertTrue(PhoneOrders.problems(pickup, now).isEmpty())
    }

    @Test
    fun `asks for the whole address for a delivery`() {
        val draft = pickup.copy(fulfilment = Fulfilment.DELIVERY, street = " ", postalCode = "4229", city = "")
        assertEquals(listOf(DraftProblem.STREET, DraftProblem.POSTAL_CODE, DraftProblem.CITY), PhoneOrders.problems(draft, now))
        assertFalse(PhoneOrders.addressComplete(draft))
        val complete = draft.copy(street = "Karl-Heine-Str. 12", postalCode = "04229", city = "Leipzig")
        assertTrue(PhoneOrders.addressComplete(complete))
        assertTrue(PhoneOrders.problems(complete, now).isEmpty())
    }

    @Test
    fun `a time must be ahead, and at most seven days`() {
        assertEquals(listOf(DraftProblem.TIME), PhoneOrders.problems(pickup.copy(scheduledFor = now), now))
        assertEquals(listOf(DraftProblem.TIME), PhoneOrders.problems(pickup.copy(scheduledFor = now.plus(Duration.ofDays(7)).plusSeconds(60)), now))
        assertTrue(PhoneOrders.problems(pickup.copy(scheduledFor = now.plus(Duration.ofDays(7))), now).isEmpty())
    }

    // ---------------------------------------------------------------------
    // The answer
    // ---------------------------------------------------------------------

    @Test
    fun `reads a dry run's price`() {
        val quote =
            PhoneOrders.quote(
                """{"id":null,"order_number":null,"total":21.24,"subtotal":20.25,"deposits":0.25,"delivery_fee":0,""" +
                    """"small_order_fee":0.99,"pickup_discount":0,"deal_discount":1,"fees_waived":false,"waived":0,""" +
                    """"delivery_zone":"near","delivery_zone_source":"geocoded","kitchen_slot":null,"promised_at":null,""" +
                    """"over_capacity":false,"dry_run":true}"""
            )
        assertNull(quote.id)
        assertNull(quote.orderNumber)
        assertEquals(21.24, quote.total)
        assertEquals(0.99, quote.smallOrderFee)
        assertEquals(1.0, quote.dealDiscount)
        assertEquals("near", quote.deliveryZone)
        assertTrue(quote.dryRun)
    }

    @Test
    fun `reads a saved order, its slot and whether the kitchen is over`() {
        val quote =
            PhoneOrders.quote(
                """{"id":"9c1","order_number":412,"total":17.5,"subtotal":17.5,"deposits":0,"delivery_fee":0,""" +
                    """"small_order_fee":0,"pickup_discount":0,"deal_discount":0,"fees_waived":true,"waived":1.49,""" +
                    """"delivery_zone":"near","delivery_zone_source":"geocoded",""" +
                    """"kitchen_slot":"2026-10-04T16:15:00+00:00","promised_at":"2026-10-04T16:30:00.5+00:00",""" +
                    """"over_capacity":true,"dry_run":false}"""
            )
        assertEquals("9c1", quote.id)
        assertEquals(412L, quote.orderNumber)
        assertTrue(quote.feesWaived)
        assertEquals(1.49, quote.waived)
        assertEquals(at("2026-10-04T16:15:00Z"), quote.kitchenSlot)
        assertTrue(quote.overCapacity)
        assertFalse(quote.dryRun)
    }

    // ---------------------------------------------------------------------
    // The refusals
    // ---------------------------------------------------------------------

    @Test
    fun `maps every refusal of staff_place_order by its code`() {
        val codes =
            mapOf(
                "42501" to PhoneOrderError.NOT_STAFF,
                "HB467" to PhoneOrderError.BAD_VALUE,
                "HB468" to PhoneOrderError.CONTACT,
                "HB469" to PhoneOrderError.ETA,
                "HB470" to PhoneOrderError.TIME,
                "HB471" to PhoneOrderError.BASKET,
                "HB472" to PhoneOrderError.ADDRESS_INCOMPLETE,
                "HB426" to PhoneOrderError.OUTSIDE,
                "HB427" to PhoneOrderError.MINIMUM,
                "HB428" to PhoneOrderError.UNCHECKED,
                "HB409" to PhoneOrderError.TOTAL_CHANGED,
            )
        for ((code, reason) in codes) {
            assertEquals(reason, PhoneOrders.error(BackendException(400, "staff_place_order: x", code))?.reason, code)
        }
        assertNull(PhoneOrders.error(BackendException(400, "x", "HB999")))
        assertNull(PhoneOrders.error(IllegalStateException("x")))
        // "staff only" is read as NotAllowedException before a code is looked at.
        assertEquals(PhoneOrderError.NOT_STAFF, PhoneOrders.error(NotAllowedException())?.reason)
    }

    @Test
    fun `reads the last ring's minimum out of HB427`() {
        val e = PhoneOrders.error(BackendException(400, "staff_place_order: orders to this address start at 25.00", "HB427"))
        assertEquals(25.0, e?.minimum)
    }

    // ---------------------------------------------------------------------
    // The minutes
    // ---------------------------------------------------------------------

    @Test
    fun `pre-selects the minutes as for an incoming order`() {
        val prep = mapOf(1L to 10, 3L to 0)
        // Two Döner: 10 + 2, rounded up to 15.
        assertEquals(15, PhoneOrders.suggestedEta(pickup, null, prep, 0))
        // Delivered to the near ring: 12 + 12 riding, 25; busy mode adds its 10.
        val delivery = pickup.copy(fulfilment = Fulfilment.DELIVERY, postalCode = "04229")
        assertEquals(35, PhoneOrders.suggestedEta(delivery, "near", prep, 10))
        assertEquals(Eta.quote(12, Eta.travelMinutes("04229", null)), PhoneOrders.suggestedEta(delivery, null, prep, 0))
    }

    // ---------------------------------------------------------------------
    // check-address
    // ---------------------------------------------------------------------

    @Test
    fun `reads check-address as the site does`() {
        assertEquals(AddressCheck("near", AddressStatus.VERIFIED), PhoneOrders.verdict("""{"zone":"near","status":"verified"}"""))
        assertEquals(
            AddressCheck("outside", AddressStatus.VERIFIED, "04318"),
            PhoneOrders.verdict("""{"zone":"outside","status":"verified","postal_code":"04318"}"""),
        )
        assertTrue(PhoneOrders.verdict("""{"zone":"outside"}""").outside)
        assertEquals(AddressCheck(null, AddressStatus.UNVERIFIED), PhoneOrders.verdict("""{"zone":"unknown","status":"unverified"}"""))
        assertEquals(AddressCheck(null, AddressStatus.UNCHECKED), PhoneOrders.verdict("""{"zone":"unknown","status":"unchecked"}"""))
        assertEquals(AddressCheck(null, AddressStatus.UNCHECKED), PhoneOrders.verdict("not json"))
    }

    // ---------------------------------------------------------------------
    // Over the wire
    // ---------------------------------------------------------------------

    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabasePhoneOrderBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    @Test
    fun `prices and saves through staff_place_order as the signed-in account`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"id":null,"order_number":null,"total":16,"dry_run":true}""")

        val quote = backend.place(pickup, null, null, dryRun = true)
        assertEquals(16.0, quote.total)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_place_order", request.url.encodedPath)
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertTrue(request.body!!.utf8().contains(""""p_dry_run":true"""))
    }

    @Test
    fun `a refusal comes back in words to map, and a missing function as a server update`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB409","message":"prices changed while ordering: shown 16, current 17"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function"}""")
        test.reply(403, """{"code":"42501","message":"staff only"}""")

        assertEquals(PhoneOrderError.TOTAL_CHANGED, assertFailsWith<PhoneOrderException> { backend.place(pickup, 20, 16.0, false) }.reason)
        assertEquals(PhoneOrders.MIGRATION, assertFailsWith<NeedsServerUpdateException> { backend.place(pickup, 20, 16.0, false) }.migration)
        assertEquals(PhoneOrderError.NOT_STAFF, assertFailsWith<PhoneOrderException> { backend.place(pickup, 20, 16.0, false) }.reason)
    }

    @Test
    fun `checks the address with the site's function, as the signed-in account`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"zone":"far","status":"verified"}""")

        assertEquals(AddressCheck("far", AddressStatus.VERIFIED), backend.checkAddress(" Lindenauer Markt 1", "04177", "Leipzig "))

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/functions/v1/check-address", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("anon-key", request.headers["apikey"])
        assertEquals("""{"street":"Lindenauer Markt 1","postal_code":"04177","city":"Leipzig"}""", request.body!!.utf8())
    }

    @Test
    fun `reads the menu with what its ingredients have sold out`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/menu_categories" to """[{"id":1,"name":"Döner"}]""",
                "/rest/v1/menu_items" to
                    """[{"id":1,"category_id":1,"name":"Döner","price":7.5,"prep_minutes":8,"sort_order":0,"available":true}]""",
                "/rest/v1/menu_item_tags" to "[]",
                "/rest/v1/menu_item_option_groups" to """[{"item_id":1,"group_id":10}]""",
                "/rest/v1/menu_option_groups" to """[{"id":10,"name":"Dein Fleisch","selection":"single"}]""",
                "/rest/v1/menu_options" to
                    """[{"id":101,"group_id":10,"name":"Kalb","price":0,"available":true},{"id":102,"group_id":10,"name":"Lamm","price":1,"available":true}]""",
                "/rest/v1/menu_option_tags" to "[]",
                "/rest/v1/menu_item_stock" to """[{"id":1,"available":true}]""",
                "/rest/v1/menu_option_stock" to """[{"id":101,"available":false},{"id":102,"available":true}]""",
            )
        )

        val menu = backend.menu()
        assertTrue(menu.dishes.single().available)
        assertEquals(listOf(false, true), menu.groups.single().options.map { it.available })
        assertEquals(listOf(102L), OptionRules.preset(OptionRules.groups(menu.dishes.single(), menu.groups))[10])
    }

    // ---------------------------------------------------------------------
    // The column fallback
    // ---------------------------------------------------------------------

    @Test
    fun `names the column a database does not have`() {
        assertEquals("source", ColumnLadder.missingColumnName("orders: column orders.source does not exist"))
        assertEquals("kitchen_slot", ColumnLadder.missingColumnName("column orders.kitchen_slot does not exist"))
        assertEquals("dine_in", ColumnLadder.missingColumnName("""column "dine_in" does not exist"""))
        assertNull(ColumnLadder.missingColumnName("something else"))
        assertNull(ColumnLadder.missingColumnName(null))
    }

    @Test
    fun `steps down to the first column list without the missing column, and one for a column it cannot name`() = runBlocking<Unit> {
        val ladder = ColumnLadder(listOf("a,b,c", "a,b", "a"))
        var asked = mutableListOf<String>()
        val answer =
            ladder.read { columns ->
                asked += columns
                if ("b" in columns.split(",")) throw BackendException(400, "x: column orders.b does not exist", "42703")
                "read $columns"
            }
        assertEquals("read a", answer)
        assertEquals(listOf("a,b,c", "a"), asked)
        assertEquals("a", ladder.current)

        val other = ColumnLadder(listOf("a,b,c", "a,b", "a"))
        asked = mutableListOf()
        other.read { columns ->
            asked += columns
            if (columns == "a,b,c") throw BackendException(400, "x: no such thing", "42703")
            columns
        }
        assertEquals(listOf("a,b,c", "a,b"), asked)
    }

    @Test
    fun `any other refusal, and one on the oldest list, goes on up`() = runBlocking<Unit> {
        val ladder = ColumnLadder(listOf("a,b", "a"))
        assertFailsWith<BackendException> { ladder.read<Unit> { throw BackendException(500, "down", null) } }
        assertEquals("a,b", ladder.current)
        assertFailsWith<BackendException> {
            ladder.read<Unit> { throw BackendException(400, "column orders.a does not exist", "42703") }
        }
        assertEquals("a", ladder.current)
    }
}
