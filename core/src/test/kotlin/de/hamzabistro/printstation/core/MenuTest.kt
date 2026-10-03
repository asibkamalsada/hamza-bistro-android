package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MenuTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseMenuBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    @Test
    fun `reads every dish in menu order, sold out or not, with its tags`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/menu_categories" to """[{"id":2,"name":"Döner"},{"id":1,"name":"Getränke"}]""",
                "/rest/v1/menu_items" to
                    """[{"id":10,"category_id":1,"name":"Cola","description":null,"price":2.5,"prep_minutes":0,"sort_order":10,"available":true,"image_url":null},""" +
                    """{"id":4,"category_id":2,"name":"Döner Teller","description":"mit Salat","price":11.9,"prep_minutes":8,"sort_order":20,"available":false,"image_url":"https://x/y.webp"},""" +
                    """{"id":3,"category_id":2,"name":"Döner","description":"","price":7.5,"prep_minutes":5,"sort_order":10,"available":true,"image_url":""},""" +
                    """{"id":99,"category_id":null,"name":"Halb fertig","price":1,"available":true}]""",
                "/rest/v1/menu_item_tags" to """[{"item_id":3,"tag_id":1},{"item_id":3,"tag_id":2}]""",
                "/rest/v1/menu_item_option_groups" to """[{"item_id":3,"group_id":7},{"item_id":3,"group_id":5}]""",
            )
        )

        val dishes = backend.dishes()
        // Category by category, in the categories' order; within one, as the database sorted.
        assertEquals(listOf("Döner Teller", "Döner", "Cola"), dishes.map { it.name })
        assertEquals("Döner", dishes[0].category)
        assertFalse(dishes[0].available)
        assertEquals(listOf(1L, 2L), dishes[1].tags)
        assertEquals("", dishes[2].description)
        assertEquals(
            DishEdit("Döner Teller", "mit Salat", 11.9, 8, "https://x/y.webp", available = false, categoryId = 2),
            dishes[0].edit,
        )
        // In the order the dish offers them, as the server sorted them.
        assertEquals(listOf(7L, 5L), dishes[1].groupIds)

        val items = test.requests().single { it.url.encodedPath == "/rest/v1/menu_items" }
        assertEquals("sort_order,name", items.url.queryParameter("order"))
        assertEquals("is.null", items.url.queryParameter("archived_at"))
        assertEquals("Bearer access-1", items.headers["Authorization"])
    }

    @Test
    fun `switches a dish off, and says so when the switch did not stick`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"id":4}]""")
        // What PostgREST answers when the policies let nothing change.
        test.reply(200, "[]")

        backend.setDishAvailable(4, false)
        assertFailsWith<NotAllowedException> { backend.setDishAvailable(4, true) }

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/rest/v1/menu_items", request.url.encodedPath)
        assertEquals("eq.4", request.url.queryParameter("id"))
        assertEquals("return=representation", request.headers["Prefer"])
        assertEquals("""{"available":false}""", request.body!!.utf8())
    }

    @Test
    fun `saves what changed about a dish through update_menu_item, trimmed`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"id":4}""")

        val before = DishEdit("Döner Teller", "mit Salat", 11.9, 8, "https://x/y.webp")
        backend.saveDish(4, before, before.copy(name = "  Döner Teller ", description = " mit Salat und Soße ", price = 12.5))
        // Nothing changed: nothing sent.
        backend.saveDish(4, before, before.copy(name = "Döner Teller "))

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/update_menu_item", request.url.encodedPath)
        assertEquals("""{"p_item_id":4,"p_changes":{"description":"mit Salat und Soße","price":12.5}}""", request.body!!.utf8())
        assertNull(test.server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `reads and saves a drink's size, and clears it with null`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/menu_categories" to """[{"id":1,"name":"Getränke"}]""",
                "/rest/v1/menu_items" to
                    """[{"id":10,"category_id":1,"name":"Cola 0,33l","price":2.4,"deposit":0.25,"volume_ml":330},""" +
                    """{"id":11,"category_id":1,"name":"Ayran","price":1.5,"volume_ml":null}]""",
                "/rest/v1/menu_item_tags" to "[]",
                "/rest/v1/menu_item_option_groups" to "[]",
            )
        )
        val dishes = backend.dishes()
        assertEquals(listOf(330, null), dishes.map { it.volumeMl })
        assertEquals(0.25, dishes[0].deposit)
        assertEquals(330, dishes[0].edit.volumeMl)
        assertEquals("0,33 l · 6,52\u00a0€/l", dishes[0].unitPrice)
        assertNull(dishes[1].unitPrice)
        val items = test.requests().single { it.url.encodedPath == "/rest/v1/menu_items" }
        val columns = items.url.queryParameter("select")!!.split(",")
        assertTrue("volume_ml" in columns && "deposit" in columns)
    }

    @Test
    fun `saves a drink's size in millilitres, and clears it with null`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "{}")
        test.reply(200, "{}")

        val cola = DishEdit("Cola 0,33l", "", 2.4, 0, "")
        backend.saveDish(10, cola, cola.copy(volumeMl = 330))
        backend.saveDish(10, cola.copy(volumeMl = 330), cola)

        test.server.takeRequest()
        assertEquals("""{"p_item_id":10,"p_changes":{"volume_ml":330}}""", test.server.takeRequest().body!!.utf8())
        assertEquals("""{"p_item_id":10,"p_changes":{"volume_ml":null}}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `a dish edit is refused before it is sent when it cannot be right`() {
        val edit = DishEdit("Döner", "", 7.5, 5, "")
        assertTrue(edit.valid)
        assertEquals(listOf(DishProblem.NAME), edit.copy(name = " D ").problems)
        assertEquals(listOf(DishProblem.PRICE, DishProblem.DEPOSIT), edit.copy(price = -1.0).problems)
        assertEquals(listOf(DishProblem.PREP), edit.copy(prepMinutes = 121).problems)
        // The Pfand is part of the price, never more.
        assertEquals(listOf(DishProblem.DEPOSIT), edit.copy(price = 0.2, deposit = 0.25).problems)
        assertTrue(edit.copy(price = 0.25, deposit = 0.25).valid)
        assertEquals(listOf(DishProblem.PICKUP_DISCOUNT), edit.copy(pickupDiscount = -1.0).problems)
        assertEquals(listOf(DishProblem.VOLUME), edit.copy(volumeMl = 0).problems)
        assertEquals(listOf(DishProblem.ADDITIVES), edit.copy(additives = listOf(1, 6)).problems)
    }

    @Test
    fun `tags a dish with exactly the set the form holds`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(204)
        test.reply(201)
        test.reply(204)

        backend.setDishTags(3, listOf(1, 3))
        backend.setDishTags(3, emptyList())

        test.server.takeRequest()
        val stale = test.server.takeRequest()
        assertEquals("DELETE", stale.method)
        assertEquals("eq.3", stale.url.queryParameter("item_id"))
        assertEquals("not.in.(1,3)", stale.url.queryParameter("tag_id"))
        val added = test.server.takeRequest()
        assertEquals("POST", added.method)
        assertEquals("item_id,tag_id", added.url.queryParameter("on_conflict"))
        assertEquals("return=minimal,resolution=ignore-duplicates", added.headers["Prefer"])
        assertEquals("""[{"item_id":3,"tag_id":1},{"item_id":3,"tag_id":3}]""", added.body!!.utf8())
        // No tags: every tag goes, and nothing is added.
        val cleared = test.server.takeRequest()
        assertEquals("DELETE", cleared.method)
        assertEquals(null, cleared.url.queryParameter("tag_id"))
    }

    @Test
    fun `puts a photo in the menu bucket under a name never used before`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"Key":"menu/item-4/1700000000000.webp"}""")

        val url = backend.uploadPhoto(4, Photo(byteArrayOf(1, 2, 3), "image/webp"), now = 1_700_000_000_000)

        assertEquals(test.config.url.resolve("/storage/v1/object/public/menu/item-4/1700000000000.webp").toString(), url)
        test.server.takeRequest()
        val upload = test.server.takeRequest()
        assertEquals("POST", upload.method)
        assertEquals("/storage/v1/object/menu/item-4/1700000000000.webp", upload.url.encodedPath)
        assertEquals("image/webp", upload.headers["Content-Type"])
        assertEquals("max-age=31536000", upload.headers["cache-control"])
        assertEquals(3, upload.body!!.size)
    }

    @Test
    fun `reads the choices by group, with the dishes each reaches`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/menu_option_groups" to """[{"id":1,"name":"Dein Fleisch"},{"id":2,"name":"Leer"},{"id":3,"name":"Niemandes"}]""",
                "/rest/v1/menu_options" to
                    """[{"id":11,"group_id":1,"name":"Kalb","price":0,"available":true},{"id":12,"group_id":1,"name":"Hähnchen","price":0,"available":false},""" +
                    """{"id":31,"group_id":3,"name":"Rest","price":0,"available":true}]""",
                "/rest/v1/menu_item_option_groups" to """[{"item_id":5,"group_id":1},{"item_id":3,"group_id":1},{"item_id":3,"group_id":2}]""",
                "/rest/v1/menu_items" to """[{"id":3,"name":"Döner"},{"id":5,"name":"Dürüm"}]""",
                "/rest/v1/menu_option_tags" to """[{"option_id":11,"tag_id":2}]""",
            )
        )

        val groups = backend.optionGroups()
        // All of them, for the editor; only one is something a customer meets.
        assertEquals(listOf("Dein Fleisch", "Leer", "Niemandes"), groups.map { it.name })
        assertEquals(listOf("Dein Fleisch"), groups.filter { it.offered }.map { it.name })
        assertEquals(listOf("Döner", "Dürüm"), groups[0].dishes)
        assertEquals(listOf(5L, 3L), groups[0].itemIds)
        assertEquals(listOf("Kalb", "Hähnchen"), groups[0].options.map { it.name })
        assertEquals(listOf(2L), groups[0].options[0].tags)
        assertFalse(groups[0].options[1].available)
    }

    @Test
    fun `switches a choice off everywhere, and marks it`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"id":12}]""")
        test.reply(201)
        test.reply(204)

        backend.setOptionAvailable(12, false)
        backend.setOptionTag(12, 2, on = true)
        backend.setOptionTag(12, 2, on = false)

        test.server.takeRequest()
        val off = test.server.takeRequest()
        assertEquals("/rest/v1/menu_options", off.url.encodedPath)
        assertEquals("""{"available":false}""", off.body!!.utf8())
        val tag = test.server.takeRequest()
        assertEquals("/rest/v1/menu_option_tags", tag.url.encodedPath)
        assertEquals("""[{"option_id":12,"tag_id":2}]""", tag.body!!.utf8())
        val untag = test.server.takeRequest()
        assertEquals("DELETE", untag.method)
        assertEquals("eq.12", untag.url.queryParameter("option_id"))
        assertEquals("eq.2", untag.url.queryParameter("tag_id"))
    }

    @Test
    fun `reads the ingredients with what each is used in`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/ingredients" to """[{"id":1,"name":"Hähnchen","in_stock":false,"sort_order":1},{"id":2,"name":"Käse","in_stock":true,"sort_order":2}]""",
                "/rest/v1/ingredient_items" to """[{"ingredient_id":1,"item_id":3},{"ingredient_id":1,"item_id":5}]""",
                "/rest/v1/ingredient_options" to """[{"ingredient_id":1,"option_id":12}]""",
            )
        )

        val ingredients = backend.ingredients()
        assertEquals(Ingredient(1, "Hähnchen", false, 1, listOf(3, 5), listOf(12)), ingredients[0])
        assertEquals(Ingredient(2, "Käse", true, 2, emptyList(), emptyList()), ingredients[1])
    }

    @Test
    fun `adds an ingredient, switches it, and deletes it`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(201, """[{"id":7,"name":"Falafel","in_stock":true,"sort_order":0}]""")
        test.reply(200, """[{"id":7}]""")
        test.reply(204)

        assertEquals(Ingredient(7, "Falafel", true, 0, emptyList(), emptyList()), backend.addIngredient("  Falafel "))
        backend.setInStock(7, false)
        backend.removeIngredient(7)

        test.server.takeRequest()
        val add = test.server.takeRequest()
        assertEquals("""{"name":"Falafel"}""", add.body!!.utf8())
        assertEquals("return=representation", add.headers["Prefer"])
        assertEquals("""{"in_stock":false}""", test.server.takeRequest().body!!.utf8())
        val remove = test.server.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals("eq.7", remove.url.queryParameter("id"))
    }

    @Test
    fun `replaces what an ingredient is used in`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/ingredient_items" to "",
                "/rest/v1/ingredient_options" to "",
            )
        )

        backend.setIngredientLinks(1, dishIds = listOf(3, 5), optionIds = emptyList())

        val sent = test.requests().filterNot { it.url.encodedPath == "/auth/v1/token" }
        // Both cleared before anything is added.
        assertEquals(listOf("DELETE", "DELETE"), sent.take(2).map { it.method })
        assertTrue(sent.take(2).all { it.url.queryParameter("ingredient_id") == "eq.1" })
        val added = sent.drop(2).single()
        assertEquals("/rest/v1/ingredient_items", added.url.encodedPath)
        assertEquals("""[{"ingredient_id":1,"item_id":3},{"ingredient_id":1,"item_id":5}]""", added.body!!.utf8())
    }

    @Test
    fun `keeps not stated, none and letters apart on dishes and choices`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/menu_categories" to """[{"id":1,"name":"Döner"}]""",
                "/rest/v1/menu_items" to
                    """[{"id":3,"category_id":1,"name":"Döner","price":7.5,"allergens":null},""" +
                    """{"id":4,"category_id":1,"name":"Cola","price":2.5,"allergens":[]},""" +
                    """{"id":5,"category_id":1,"name":"Dürüm","price":8,"allergens":["a","g"]},""" +
                    // A column the server does not send yet reads as "not stated", never as "none".
                    """{"id":6,"category_id":1,"name":"Alt","price":1}]""",
                "/rest/v1/menu_item_tags" to "[]",
                "/rest/v1/menu_option_groups" to """[{"id":1,"name":"Extra Zutat"}]""",
                "/rest/v1/menu_options" to
                    """[{"id":11,"group_id":1,"name":"extra Käse","price":1,"available":true,"allergens":["g"]},""" +
                    """{"id":12,"group_id":1,"name":"extra Zwiebeln","price":0.5,"available":true,"allergens":[]},""" +
                    """{"id":13,"group_id":1,"name":"Kräutersauce","price":0,"available":true,"allergens":null}]""",
                "/rest/v1/menu_item_option_groups" to """[{"item_id":3,"group_id":1}]""",
                "/rest/v1/menu_option_tags" to "[]",
            )
        )

        val dishes = backend.dishes()
        assertEquals(listOf(null, emptyList(), listOf("a", "g"), null), dishes.map { it.allergens })
        assertEquals(2, Allergens.missing(dishes.map { it.allergens }))
        val items = test.requests().first { it.url.encodedPath == "/rest/v1/menu_items" }
        assertTrue(items.url.queryParameter("select")!!.split(",").contains("allergens"))

        val options = backend.optionGroups().single().options
        assertEquals(listOf(listOf("g"), emptyList(), null), options.map { it.allergens })
        val optionRows = test.requests().single { it.url.encodedPath == "/rest/v1/menu_options" }
        assertTrue(optionRows.url.queryParameter("select")!!.split(",").contains("allergens"))
    }

    @Test
    fun `reads the 14 allergens in the menu's order`() = runBlocking<Unit> {
        test.routes(
            mapOf(
                "/rest/v1/allergens" to
                    """[{"code":"a","name_de":"Glutenhaltiges Getreide","name_en":"Cereals containing gluten"},""" +
                    """{"code":"g","name_de":"Milch (einschließlich Laktose)","name_en":"Milk (including lactose)"}]"""
            )
        )

        val allergens = backend.allergens()
        assertEquals(listOf("a", "g"), allergens.map { it.code })
        assertEquals("Milch (einschließlich Laktose)", allergens[1].nameDe)
        val request = test.requests().single { it.url.encodedPath == "/rest/v1/allergens" }
        assertEquals("code,name_de,name_en", request.url.queryParameter("select"))
        assertEquals("sort_order", request.url.queryParameter("order"))
    }

    @Test
    fun `states allergens through the staff functions, null included`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """["a","g"]""")
        test.reply(200, "[]")
        test.reply(200, "null")

        assertEquals(listOf("a", "g"), backend.setDishAllergens(12, listOf("g", "a")))
        assertEquals(emptyList(), backend.setOptionAllergens(34, emptyList()))
        assertNull(backend.setDishAllergens(12, null))

        test.server.takeRequest()
        val letters = test.server.takeRequest()
        assertEquals("POST", letters.method)
        assertEquals("/rest/v1/rpc/set_item_allergens", letters.url.encodedPath)
        assertEquals("""{"p_item_id":12,"p_codes":["g","a"]}""", letters.body!!.utf8())
        val none = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/set_option_allergens", none.url.encodedPath)
        assertEquals("""{"p_option_id":34,"p_codes":[]}""", none.body!!.utf8())
        // "Not stated" is sent, as null — not left out, which PostgREST would refuse.
        assertEquals("""{"p_item_id":12,"p_codes":null}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `says which allergen the database does not know, and who may not set them`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB433","message":"there is no allergen \"z\""}""")
        test.reply(400, """{"code":"P0001","message":"staff only"}""")

        assertFailsWith<UnknownAllergenException> { backend.setDishAllergens(12, listOf("z")) }
        assertFailsWith<NotAllowedException> { backend.setOptionAllergens(34, emptyList()) }
    }

    @Test
    fun `an account that is not staff may not change the menu`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(403, """{"code":"42501","message":"new row violates row-level security policy for table \"menu_item_tags\""}""")
        val refused = assertFailsWith<BackendException> { backend.setDishTags(3, emptyList()) }
        assertEquals(403, refused.status)
    }
}
