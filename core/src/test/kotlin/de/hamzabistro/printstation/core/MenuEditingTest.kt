package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MenuEditingTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseMenuBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    /** The requests after the session's refresh, as path and body. */
    private fun sent(): List<Pair<String, String>> =
        test.requests().filterNot { it.url.encodedPath == "/auth/v1/token" }.map { it.url.encodedPath to (it.body?.utf8() ?: "") }

    // -------------------------------------------------------------------
    // The form's rules
    // -------------------------------------------------------------------

    @Test
    fun `reads euros as typed, and nothing that is not one`() {
        assertEquals(7.5, MenuEdits.parseEuro("7,50"))
        assertEquals(7.5, MenuEdits.parseEuro(" 7.5 "))
        assertEquals(12.0, MenuEdits.parseEuro("12 €"))
        assertEquals(0.25, MenuEdits.parseEuro("0,25"))
        assertNull(MenuEdits.parseEuro(""))
        assertNull(MenuEdits.parseEuro("7,"))
        assertNull(MenuEdits.parseEuro("-1"))
        assertNull(MenuEdits.parseEuro("1,005"))
        assertNull(MenuEdits.parseEuro("sieben"))
        assertEquals("7,50", MenuEdits.euroText(7.5))
    }

    @Test
    fun `a new dish starts with what most of its category has`() {
        fun dish(pickup: Double, prep: Int, archived: Boolean = false) =
            MenuDish(0, "Döner", "x", "", 7.5, prep, 0, "", true, emptyList(), pickupDiscount = pickup, archived = archived)

        val doner = listOf(dish(1.0, 5), dish(1.0, 8), dish(0.0, 5), dish(0.0, 12, archived = true), dish(0.0, 12, archived = true))
        assertEquals(1.0 to 5, MenuEdits.categoryDefaults(doner))
        // A tie goes to the smaller, as Postgres' mode() does.
        assertEquals(0.0 to 5, MenuEdits.categoryDefaults(listOf(dish(1.0, 8), dish(0.0, 5))))
        // An empty category: the column defaults.
        assertEquals(0.0 to MenuEdits.DEFAULT_PREP, MenuEdits.categoryDefaults(emptyList()))
    }

    @Test
    fun `moves one row up or down and stops at the ends`() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(listOf(2L, 1L, 3L), MenuEdits.move(ids, 2, -1))
        assertEquals(listOf(1L, 3L, 2L), MenuEdits.move(ids, 2, 1))
        assertEquals(ids, MenuEdits.move(ids, 1, -1))
        assertEquals(ids, MenuEdits.move(ids, 3, 1))
        assertEquals(ids, MenuEdits.move(ids, 9, 1))
    }

    @Test
    fun `the week reads Monday first, with the database's numbers`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 0), MenuEdits.WEEK)
    }

    @Test
    fun `ticks additives on and off, sorted, and only the five the legend has`() {
        assertEquals(listOf(1, 4), Additives.toggle(listOf(4), 1))
        assertEquals(listOf(4), Additives.toggle(listOf(1, 4), 1))
        assertTrue(Additives.allowed(listOf(1, 5)))
        assertFalse(Additives.allowed(listOf(0)))
        assertFalse(OptionEdit("Käse", 1.0, listOf(9)).valid)
        assertFalse(OptionEdit(" ", 1.0).valid)
        assertFalse(OptionEdit("Käse", -0.5).valid)
        assertTrue(OptionEdit("Käse", 0.0).valid)
    }

    @Test
    fun `a deal needs a day, something off, and something to take it off`() {
        assertTrue(MenuEdits.dealValid(1, 1.0, 3, null))
        assertTrue(MenuEdits.dealValid(0, 0.5, null, 12))
        assertFalse(MenuEdits.dealValid(7, 1.0, 3, null))
        assertFalse(MenuEdits.dealValid(1, 0.0, 3, null))
        assertFalse(MenuEdits.dealValid(1, null, 3, null))
        assertFalse(MenuEdits.dealValid(1, 1.0, null, null))
    }

    @Test
    fun `a dish form reads as typed and marks every field that would be refused`() {
        val dish =
            MenuDish(4, "Getränke", "Cola", "", 2.4, 0, 10, "", true, listOf(1), allergens = emptyList(), volumeMl = 330, deposit = 0.25, categoryId = 1, groupIds = listOf(7))
        val form = DishForm.of(dish)
        assertEquals("2,40", form.price)
        assertEquals("0,25", form.deposit)
        assertEquals("", form.pickupDiscount)
        assertEquals(dish.edit, form.edit)
        assertEquals(emptySet(), form.problems)

        // "7," on the way to "7,50" is not a price yet.
        assertEquals(setOf(DishProblem.PRICE), form.copy(price = "7,").problems)
        assertNull(form.copy(price = "7,").edit)
        assertEquals(setOf(DishProblem.DEPOSIT), form.copy(deposit = "3").problems)
        assertEquals(setOf(DishProblem.PREP), form.copy(prepMinutes = "").problems)
        assertEquals(setOf(DishProblem.VOLUME), form.copy(volumeMl = "0,33").problems)
        assertEquals(setOf(DishProblem.NAME, DishProblem.PICKUP_DISCOUNT), form.copy(name = "C", pickupDiscount = "x").problems)
        // Blank Pfand and blank discount are none.
        assertEquals(0.0, form.copy(deposit = " ").edit?.deposit)
    }

    @Test
    fun `a new dish's form starts empty, in its category, with the category's usual values`() {
        val doner = listOf(MenuDish(3, "Döner", "Döner", "", 7.5, 5, 10, "", true, emptyList(), categoryId = 2, pickupDiscount = 1.0))
        val form = DishForm.new(2, doner)
        assertEquals(2L, form.categoryId)
        assertEquals("1,00", form.pickupDiscount)
        assertEquals("5", form.prepMinutes)
        assertNull(form.allergens)
        assertNull(form.edit)
        assertEquals(DishEdit("Falafel", "", 7.0, 5, "", pickupDiscount = 1.0, categoryId = 2), form.copy(name = "Falafel", price = "7").edit)
    }

    @Test
    fun `a choice's form takes a blank price as free`() {
        val form = OptionForm.new(7)
        assertNull(form.edit)
        assertEquals(OptionEdit("Knoblauch", 0.0), form.copy(name = "Knoblauch").edit)
        assertEquals(OptionEdit("Käse", 1.0, listOf(1)), form.copy(name = "Käse", price = "1", additives = listOf(1)).edit)
        assertNull(form.copy(name = "Käse", price = "1,").edit)
        val read = OptionForm.of(7, MenuOption(71, "Käse", 1.5, false, emptyList(), additives = listOf(2)))
        assertEquals(OptionEdit("Käse", 1.5, listOf(2), available = false), read.edit)
    }

    @Test
    fun `a deal's form`() {
        assertEquals(DealForm(1, null, null, ""), DealForm.of(1, null))
        val form = DealForm.of(3, MenuDeal(3, 2, 4, 1.5))
        assertEquals("1,50", form.discount)
        assertTrue(form.valid)
        assertFalse(form.copy(discount = "0").valid)
        assertFalse(form.copy(categoryId = null, itemId = null).valid)
    }

    @Test
    fun `a group and a category need a name`() {
        assertFalse(GroupForm.new().valid)
        assertEquals(Selection.SINGLE, GroupForm.new().selection)
        val group = OptionGroup(7, "Soße", emptyList(), listOf("Döner"), Selection.MULTIPLE, itemIds = listOf(3))
        assertEquals(GroupForm(7, "Soße", Selection.MULTIPLE, listOf(3)), GroupForm.of(group))
        assertFalse(CategoryForm.new().copy(name = "  ").valid)
        assertTrue(CategoryForm.of(MenuCategory(2, "Döner")).valid)
        assertEquals(Selection.MULTIPLE, Selection.of("something new"))
    }

    // -------------------------------------------------------------------
    // What is sent
    // -------------------------------------------------------------------

    @Test
    fun `sends only what changed about a dish, and every field for a new one`() {
        val before = DishEdit("Döner", "", 7.5, 5, "", categoryId = 2)
        assertEquals("{}", MenuEdits.dishChanges(before, before).toString())
        assertEquals(
            """{"category_id":3,"deposit":0.25,"pickup_discount":1.0,"additives":[1,4],"available":false}""",
            MenuEdits.dishChanges(
                before,
                before.copy(categoryId = 3, deposit = 0.25, pickupDiscount = 1.0, additives = listOf(1, 4), available = false),
            ).toString(),
        )
        assertEquals(
            """{"category_id":2,"name":"Döner","description":"","price":7.5,"deposit":0.0,"pickup_discount":0.0,""" +
                """"prep_minutes":5,"volume_ml":null,"additives":[],"image_url":"","available":true}""",
            MenuEdits.dishChanges(null, before).toString(),
        )
    }

    @Test
    fun `creates a dish with the name, category and price on their own`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "42")

        val id = backend.createDish(2, DishEdit(" Falafel Teller ", "mit Salat", 9.9, 8, "", pickupDiscount = 1.0, additives = listOf(2)))

        assertEquals(42, id)
        val (path, body) = sent().single()
        assertEquals("/rest/v1/rpc/create_menu_item", path)
        assertEquals(
            """{"p_category_id":2,"p_name":"Falafel Teller","p_price":9.9,"p_fields":{"description":"mit Salat","deposit":0.0,""" +
                """"pickup_discount":1.0,"prep_minutes":8,"volume_ml":null,"additives":[2],"image_url":"","available":true}}""",
            body,
        )
    }

    @Test
    fun `archives, restores, reorders and sets a dish's groups`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        repeat(4) { test.reply(204) }

        backend.archiveDish(4)
        backend.restoreDish(4)
        backend.reorderDishes(2, listOf(5, 4, 3))
        backend.setDishGroups(4, emptyList())

        assertEquals(
            listOf(
                "/rest/v1/rpc/archive_menu_item" to """{"p_item_id":4}""",
                "/rest/v1/rpc/restore_menu_item" to """{"p_item_id":4,"p_available":true}""",
                "/rest/v1/rpc/reorder_menu_items" to """{"p_category_id":2,"p_item_ids":[5,4,3]}""",
                "/rest/v1/rpc/set_item_option_groups" to """{"p_item_id":4,"p_group_ids":[]}""",
            ),
            sent(),
        )
    }

    @Test
    fun `reads the archived dishes, and finds one by its name`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/menu_categories" to """[{"id":2,"name":"Döner"}]""",
                "/rest/v1/menu_items" to
                    """[{"id":4,"category_id":2,"name":"Döner Box","price":6.5,"available":false,"archived_at":"2026-10-03T10:00:00Z",""" +
                    """"pickup_discount":1,"additives":[1,2]}]""",
                "/rest/v1/menu_item_tags" to "[]",
                "/rest/v1/menu_item_option_groups" to "[]",
            )
        )

        val archived = backend.dishes(archived = true).single()
        assertTrue(archived.archived)
        assertEquals(1.0, archived.pickupDiscount)
        assertEquals(listOf(1, 2), archived.additives)
        val found = backend.archivedDishNamed(" Döner Box ")
        assertEquals(4L, found?.id)

        val reads = test.requests().filter { it.url.encodedPath == "/rest/v1/menu_items" }
        assertEquals("not.is.null", reads[0].url.queryParameter("archived_at"))
        assertEquals("eq.Döner Box", reads[1].url.queryParameter("name"))
        assertEquals("not.is.null", reads[1].url.queryParameter("archived_at"))
    }

    @Test
    fun `groups and choices go through their staff functions`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "7")
        test.reply(204)
        test.reply(204)
        test.reply(200, "3")
        test.reply(204)
        test.reply(204)
        test.reply(200, "71")
        test.reply(200, "{}")
        test.reply(204)
        test.reply(204)
        test.reply(204)

        assertEquals(7, backend.createGroup(" Soße ", Selection.SINGLE))
        backend.updateGroup(7, null, Selection.MULTIPLE)
        backend.updateGroup(7, "Soßen", null)
        assertEquals(3, backend.archiveGroup(7))
        backend.restoreGroup(7)
        backend.setGroupDishes(7, listOf(3, 5))
        assertEquals(71, backend.createOption(7, OptionEdit(" Knoblauch ", 0.0, listOf(1))))
        backend.saveOption(71, OptionEdit("Knoblauch", 0.0), OptionEdit("Knoblauch", 0.5))
        backend.archiveOption(71)
        backend.restoreOption(71)
        backend.reorderOptions(7, listOf(72, 71))

        assertEquals(
            listOf(
                "/rest/v1/rpc/create_option_group" to """{"p_name":"Soße","p_selection":"single"}""",
                "/rest/v1/rpc/update_option_group" to """{"p_group_id":7,"p_name":null,"p_selection":"multiple"}""",
                "/rest/v1/rpc/update_option_group" to """{"p_group_id":7,"p_name":"Soßen","p_selection":null}""",
                "/rest/v1/rpc/archive_option_group" to """{"p_group_id":7}""",
                "/rest/v1/rpc/restore_option_group" to """{"p_group_id":7}""",
                "/rest/v1/rpc/set_option_group_items" to """{"p_group_id":7,"p_item_ids":[3,5]}""",
                "/rest/v1/rpc/create_menu_option" to
                    """{"p_group_id":7,"p_name":"Knoblauch","p_price":0.0,"p_fields":{"additives":[1],"available":true}}""",
                "/rest/v1/rpc/update_menu_option" to """{"p_option_id":71,"p_changes":{"price":0.5}}""",
                "/rest/v1/rpc/archive_menu_option" to """{"p_option_id":71}""",
                "/rest/v1/rpc/restore_menu_option" to """{"p_option_id":71,"p_available":true}""",
                "/rest/v1/rpc/reorder_menu_options" to """{"p_group_id":7,"p_option_ids":[72,71]}""",
            ),
            sent(),
        )
    }

    @Test
    fun `reads a group's kind, what is archived, and the choices' additives`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/menu_option_groups" to
                    """[{"id":1,"name":"Dein Fleisch","selection":"single"},{"id":2,"name":"Alt","selection":"multiple","archived_at":"2026-10-01T00:00:00Z"}]""",
                "/rest/v1/menu_options" to
                    """[{"id":11,"group_id":1,"name":"Kalb","price":0,"available":true,"additives":[2]},""" +
                    """{"id":12,"group_id":1,"name":"Lamm","price":1,"available":false,"archived_at":"2026-10-01T00:00:00Z"}]""",
                "/rest/v1/menu_item_option_groups" to """[{"item_id":3,"group_id":1}]""",
                "/rest/v1/menu_items" to """[{"id":3,"name":"Döner"}]""",
                "/rest/v1/menu_option_tags" to "[]",
            )
        )

        val (meat, old) = backend.optionGroups()
        assertEquals(Selection.SINGLE, meat.selection)
        assertEquals(listOf(2), meat.options[0].additives)
        assertTrue(meat.options[1].archived)
        assertEquals(listOf("Kalb"), meat.live.map { it.name })
        assertTrue(meat.offered)
        assertTrue(old.archived)
        assertFalse(old.offered)
    }

    @Test
    fun `categories and deals`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/menu_categories" to """[{"id":2,"name":"Döner","image_url":"https://x/d.webp","sort_order":10},{"id":1,"name":"Getränke","image_url":null}]""",
                "/rest/v1/menu_deals" to """[{"day_of_week":1,"category_id":2,"item_id":null,"discount":1},{"day_of_week":3,"category_id":2,"item_id":4,"discount":1.5}]""",
            )
        )

        assertEquals(listOf(MenuCategory(2, "Döner", "https://x/d.webp", 10), MenuCategory(1, "Getränke", "", 0)), backend.categories())
        assertEquals(listOf(MenuDeal(1, 2, null, 1.0), MenuDeal(3, 2, 4, 1.5)), backend.deals())
    }

    @Test
    fun `writes categories and deals through their staff functions`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "9")
        repeat(6) { test.reply(204) }

        assertEquals(9, backend.createCategory(" Bowls "))
        backend.updateCategory(9, "Bowls & Salate", null)
        backend.updateCategory(9, null, "")
        backend.reorderCategories(listOf(9, 2, 1))
        backend.setDeal(1, 1.0, 2, null)
        backend.setDeal(3, 1.5, 2, 4)
        backend.clearDeal(3)

        assertEquals(
            listOf(
                "/rest/v1/rpc/create_menu_category" to """{"p_name":"Bowls"}""",
                "/rest/v1/rpc/update_menu_category" to """{"p_category_id":9,"p_name":"Bowls & Salate","p_image_url":null}""",
                "/rest/v1/rpc/update_menu_category" to """{"p_category_id":9,"p_name":null,"p_image_url":""}""",
                "/rest/v1/rpc/reorder_menu_categories" to """{"p_category_ids":[9,2,1]}""",
                "/rest/v1/rpc/set_menu_deal" to """{"p_day":1,"p_discount":1.0,"p_category_id":2,"p_item_id":null}""",
                // One dish: the database fills in its category.
                "/rest/v1/rpc/set_menu_deal" to """{"p_day":3,"p_discount":1.5,"p_category_id":null,"p_item_id":4}""",
                "/rest/v1/rpc/clear_menu_deal" to """{"p_day":3}""",
            ),
            sent(),
        )
    }

    @Test
    fun `puts a category's photo in its own folder`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "{}")

        val url = backend.uploadCategoryPhoto(9, Photo(byteArrayOf(1), "image/jpeg"), now = 5)

        assertEquals(test.config.url.resolve("/storage/v1/object/public/menu/category-9/5.jpg").toString(), url)
        assertEquals("/storage/v1/object/menu/category-9/5.jpg", sent().single().first)
    }

    // -------------------------------------------------------------------
    // Refusals
    // -------------------------------------------------------------------

    @Test
    fun `says each of the database's refusals by its code`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        for (code in listOf("HB450", "HB451", "HB452", "HB453", "HB454")) {
            test.reply(400, """{"code":"$code","message":"refused: $code"}""")
        }
        test.reply(403, """{"code":"42501","message":"staff only"}""")
        test.reply(400, """{"code":"22P02","message":"invalid input syntax"}""")

        val reasons =
            List(5) {
                assertFailsWith<MenuEditException> { backend.archiveDish(4) }.reason
            }
        assertEquals(MenuEditError.entries.toList(), reasons)
        assertFailsWith<NotAllowedException> { backend.archiveDish(4) }
        // Anything else stays what it was: a failure to show as it came.
        assertEquals("22P02", assertFailsWith<BackendException> { backend.archiveDish(4) }.code)
    }

    @Test
    fun `keeps the database's words about a value it did not allow`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB452","message":"new row for relation \"menu_items\" violates check constraint \"menu_items_deposit_within_price\""}""")

        val refused = assertFailsWith<MenuEditException> { backend.createDish(2, DishEdit("Cola", "", 0.2, 0, "")) }
        assertEquals(MenuEditError.VALUE_NOT_ALLOWED, refused.reason)
        assertTrue(refused.message!!.contains("menu_items_deposit_within_price"))
    }
}
