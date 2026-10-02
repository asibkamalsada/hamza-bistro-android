package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            )
        )

        val dishes = backend.dishes()
        // Category by category, in the categories' order; within one, as the database sorted.
        assertEquals(listOf("Döner Teller", "Döner", "Cola"), dishes.map { it.name })
        assertEquals("Döner", dishes[0].category)
        assertFalse(dishes[0].available)
        assertEquals(listOf(1L, 2L), dishes[1].tags)
        assertEquals("", dishes[2].description)
        assertEquals(DishEdit("Döner Teller", "mit Salat", 11.9, 8, 20, "https://x/y.webp"), dishes[0].edit)

        val items = test.requests().single { it.url.encodedPath == "/rest/v1/menu_items" }
        assertEquals("sort_order,name", items.url.queryParameter("order"))
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
    fun `saves what a dish is and costs, trimmed`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"id":4}]""")

        backend.saveDish(4, DishEdit("  Döner Teller ", " mit Salat ", 12.5, 9, 20, " https://x/y.webp "))

        test.server.takeRequest()
        assertEquals(
            """{"name":"Döner Teller","description":"mit Salat","price":12.5,"prep_minutes":9,"sort_order":20,"image_url":"https://x/y.webp"}""",
            test.server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `a dish edit is refused before it is sent when it cannot be right`() {
        val edit = DishEdit("Döner", "", 7.5, 5, 10, "")
        assertTrue(edit.valid)
        assertFalse(edit.copy(name = " D ").valid)
        assertFalse(edit.copy(price = -1.0).valid)
        assertFalse(edit.copy(prepMinutes = 121).valid)
        assertFalse(edit.copy(sortOrder = -10).valid)
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
        // Nothing in it, or offered by no dish: a half-finished dashboard edit.
        assertEquals(listOf("Dein Fleisch"), groups.map { it.name })
        assertEquals(listOf("Döner", "Dürüm"), groups[0].dishes)
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
    fun `an account that is not staff may not change the menu`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(403, """{"code":"42501","message":"new row violates row-level security policy for table \"menu_item_tags\""}""")
        val refused = assertFailsWith<BackendException> { backend.setDishTags(3, emptyList()) }
        assertEquals(403, refused.status)
    }
}
