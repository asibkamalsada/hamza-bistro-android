package de.hamzabistro.printstation.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A label a dish can carry — Bestseller, Vegan. Staff tag and untag; the tags
 * themselves are not theirs to add, because each needs a word in both
 * languages and a chip on the menu page.
 */
@Serializable
data class MenuTag(
    val id: Long,
    val slug: String = "",
    val name: String,
    /** Whether a choice may carry it too. False for Bestseller, which the database refuses on an option. */
    @SerialName("on_options") val onOptions: Boolean = false,
)

/** What staff may change about a dish from the app, as opposed to the dashboard. */
data class DishEdit(
    val name: String,
    val description: String,
    /** What the customer is charged: place_order reads this column. */
    val price: Double,
    /** Minutes the kitchen needs for one; 0 for anything uncooked. 0–120, as the column allows. */
    val prepMinutes: Int,
    val sortOrder: Int,
    val imageUrl: String,
) {
    /** What the form refuses before the database does: the rules of the site's form. */
    val valid: Boolean
        get() = name.trim().length >= 2 && price >= 0 && prepMinutes in 0..MAX_PREP && sortOrder >= 0

    companion object {
        const val MAX_PREP = 120
    }
}

/** A dish as staff see it — sold out or not. */
data class MenuDish(
    val id: Long,
    val category: String,
    val name: String,
    val description: String,
    val price: Double,
    val prepMinutes: Int,
    val sortOrder: Int,
    val imageUrl: String,
    val available: Boolean,
    /** The tags it carries, by id. */
    val tags: List<Long>,
) {
    val edit: DishEdit
        get() = DishEdit(name, description, price, prepMinutes, sortOrder, imageUrl)
}

/** One choice inside a group, with its own sold-out switch. */
data class MenuOption(
    val id: Long,
    val name: String,
    val price: Double,
    val available: Boolean,
    val tags: List<Long>,
)

/**
 * A group of choices. Groups are shared between dishes — "Dein Fleisch"
 * hangs off every Döner — so switching Chicken off switches it off in
 * every dish at once, and [dishes] says which.
 */
data class OptionGroup(
    val id: Long,
    val name: String,
    val options: List<MenuOption>,
    val dishes: List<String>,
)

/**
 * Something the kitchen runs out of, and everything it is used in.
 * Switching it off changes one boolean; the database works out the rest, so
 * putting it back never puts back a dish that was sold out on its own.
 */
data class Ingredient(
    val id: Long,
    val name: String,
    val inStock: Boolean,
    val sortOrder: Int,
    val dishIds: List<Long>,
    val optionIds: List<Long>,
)

/** A photo ready for the bucket: already shrunk, and what it really is. */
class Photo(val bytes: ByteArray, val contentType: String) {
    val extension: String
        get() =
            when (contentType) {
                "image/webp" -> "webp"
                "image/png" -> "png"
                else -> "jpg"
            }
}

/**
 * The menu as staff work it — the site's /menu-admin: what is sold out
 * (dishes, single choices, ingredients), and what a dish is and costs. The
 * database's policies decide; this only asks.
 */
interface MenuBackend {
    /** Every dish in menu order, sold out or not. */
    suspend fun dishes(): List<MenuDish>

    /** The labels that exist, in the order the menu shows them. */
    suspend fun tags(): List<MenuTag>

    /** Takes a dish off the menu, or puts it back. */
    suspend fun setDishAvailable(id: Long, available: Boolean)

    /** Changes what a dish is and what it costs. */
    suspend fun saveDish(id: Long, edit: DishEdit)

    /** Makes the dish carry exactly these tags. */
    suspend fun setDishTags(id: Long, tags: List<Long>)

    /** Puts a shrunk photo in the bucket; the public URL to save onto the dish. */
    suspend fun uploadPhoto(dishId: Long, photo: Photo, now: Long): String

    /** Every group with choices that some dish offers. */
    suspend fun optionGroups(): List<OptionGroup>

    /** Takes one choice off every dish that offers it, or puts it back. */
    suspend fun setOptionAvailable(id: Long, available: Boolean)

    /** Marks one choice with a tag, or unmarks it — the vegan leaf. */
    suspend fun setOptionTag(optionId: Long, tagId: Long, on: Boolean)

    suspend fun ingredients(): List<Ingredient>

    /** The everyday action: it ran out, or it turned up. */
    suspend fun setInStock(id: Long, inStock: Boolean)

    /** A new ingredient, in stock and used in nothing until its links are saved. */
    suspend fun addIngredient(name: String): Ingredient

    /** Gone, with its links: everything it held back is on the menu again. */
    suspend fun removeIngredient(id: Long)

    /** Replaces what an ingredient is used in. */
    suspend fun setIngredientLinks(id: Long, dishIds: List<Long>, optionIds: List<Long>)
}

/** [MenuBackend] over PostgREST and Storage, as the signed-in account. */
class SupabaseMenuBackend internal constructor(private val rest: SupabaseRest) : MenuBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun dishes(): List<MenuDish> = coroutineScope {
        val categories = async {
            select("menu_categories", NamedRow.serializer()) {
                addQueryParameter("select", "id,name")
                addQueryParameter("order", "sort_order")
            }
        }
        val items = async {
            select("menu_items", DishRow.serializer()) {
                addQueryParameter("select", DishRow.COLUMNS)
                addQueryParameter("order", "sort_order,name")
            }
        }
        val tags = async { select("menu_item_tags", ItemTagRow.serializer()) { addQueryParameter("select", "item_id,tag_id") } }
        val tagged = tags.await().groupBy({ it.itemId }, { it.tagId })
        val rows = items.await()
        // By category, in the categories' order, so the list reads like the menu.
        categories.await().flatMap { category ->
            rows.filter { it.categoryId == category.id }
                .map {
                    MenuDish(
                        id = it.id,
                        category = category.name,
                        name = it.name,
                        description = it.description ?: "",
                        price = it.price,
                        prepMinutes = it.prepMinutes,
                        sortOrder = it.sortOrder,
                        imageUrl = it.imageUrl ?: "",
                        available = it.available,
                        tags = tagged[it.id].orEmpty(),
                    )
                }
        }
    }

    override suspend fun tags(): List<MenuTag> =
        select("menu_tags", MenuTag.serializer()) {
            addQueryParameter("select", "id,slug,name,on_options")
            addQueryParameter("order", "sort_order,name")
        }

    override suspend fun setDishAvailable(id: Long, available: Boolean) =
        update("menu_items", id, buildJsonObject { put("available", available) })

    override suspend fun saveDish(id: Long, edit: DishEdit) =
        update(
            "menu_items",
            id,
            buildJsonObject {
                put("name", edit.name.trim())
                put("description", edit.description.trim())
                put("price", edit.price)
                put("prep_minutes", edit.prepMinutes)
                put("sort_order", edit.sortOrder)
                put("image_url", edit.imageUrl.trim())
            },
        )

    /**
     * Stated as the whole set, so a save that runs twice still ends with
     * what the screen said. Removals first, then the additions, ignoring
     * what is already there: the table has insert and delete, no update.
     */
    override suspend fun setDishTags(id: Long, tags: List<Long>) {
        delete("menu_item_tags") {
            addQueryParameter("item_id", "eq.$id")
            if (tags.isNotEmpty()) addQueryParameter("tag_id", "not.in.(${tags.joinToString(",")})")
        }
        if (tags.isEmpty()) return
        insert(
            "menu_item_tags",
            buildJsonArray {
                for (tag in tags) {
                    add(buildJsonObject {
                        put("item_id", id)
                        put("tag_id", tag)
                    })
                }
            },
            onConflict = "item_id,tag_id",
        )
    }

    /**
     * Named after the dish and the moment, never reused: a new photo is a new
     * URL, so the object can be cached for a year and the bucket needs no
     * update or delete right.
     */
    override suspend fun uploadPhoto(dishId: Long, photo: Photo, now: Long): String {
        val path = "item-$dishId/$now.${photo.extension}"
        val url = rest.endpoint("storage/v1/object/$PHOTO_BUCKET/$path").build()
        val body = photo.bytes.toRequestBody(photo.contentType.toMediaType())
        rest.call({ it.url(url).post(body).header("cache-control", "max-age=$PHOTO_CACHE_SECONDS").header("x-upsert", "false") }) { response ->
            if (!response.isSuccessful) throw rest.rejected(response.code, response.body.string(), "photo")
        }
        return rest.endpoint("storage/v1/object/public/$PHOTO_BUCKET/$path").build().toString()
    }

    override suspend fun optionGroups(): List<OptionGroup> = coroutineScope {
        val groups = async {
            select("menu_option_groups", NamedRow.serializer()) {
                addQueryParameter("select", "id,name")
                addQueryParameter("order", "name")
            }
        }
        val options = async {
            select("menu_options", OptionRow.serializer()) {
                addQueryParameter("select", "id,group_id,name,price,available")
                addQueryParameter("order", "sort_order")
            }
        }
        val links = async { select("menu_item_option_groups", GroupLinkRow.serializer()) { addQueryParameter("select", "item_id,group_id") } }
        val items = async { select("menu_items", NamedRow.serializer()) { addQueryParameter("select", "id,name") } }
        val tags = async { select("menu_option_tags", OptionTagRow.serializer()) { addQueryParameter("select", "option_id,tag_id") } }

        val names = items.await().associate { it.id to it.name }
        val dishesBy = links.await().groupBy({ it.groupId }, { names[it.itemId] }).mapValues { (_, list) -> list.filterNotNull() }
        val tagged = tags.await().groupBy({ it.optionId }, { it.tagId })
        val rows = options.await()
        groups.await()
            .map { group ->
                OptionGroup(
                    id = group.id,
                    name = group.name,
                    options =
                        rows.filter { it.groupId == group.id }
                            .map { MenuOption(it.id, it.name, it.price, it.available, tagged[it.id].orEmpty()) },
                    dishes = dishesBy[group.id].orEmpty().sorted(),
                )
            }
            // A group nobody offers, or one with nothing in it, is a
            // half-finished dashboard edit; the customer menu drops both.
            .filter { it.options.isNotEmpty() && it.dishes.isNotEmpty() }
    }

    override suspend fun setOptionAvailable(id: Long, available: Boolean) =
        update("menu_options", id, buildJsonObject { put("available", available) })

    override suspend fun setOptionTag(optionId: Long, tagId: Long, on: Boolean) {
        if (on) {
            insert(
                "menu_option_tags",
                buildJsonArray {
                    add(buildJsonObject {
                        put("option_id", optionId)
                        put("tag_id", tagId)
                    })
                },
                onConflict = "option_id,tag_id",
            )
        } else {
            delete("menu_option_tags") {
                addQueryParameter("option_id", "eq.$optionId")
                addQueryParameter("tag_id", "eq.$tagId")
            }
        }
    }

    override suspend fun ingredients(): List<Ingredient> = coroutineScope {
        val ingredients = async {
            select("ingredients", IngredientRow.serializer()) {
                addQueryParameter("select", IngredientRow.COLUMNS)
                addQueryParameter("order", "sort_order")
            }
        }
        val dishes = async { select("ingredient_items", IngredientItemRow.serializer()) { addQueryParameter("select", "ingredient_id,item_id") } }
        val options = async { select("ingredient_options", IngredientOptionRow.serializer()) { addQueryParameter("select", "ingredient_id,option_id") } }
        val dishesBy = dishes.await().groupBy({ it.ingredientId }, { it.itemId })
        val optionsBy = options.await().groupBy({ it.ingredientId }, { it.optionId })
        ingredients.await().map { it.toIngredient(dishesBy[it.id].orEmpty(), optionsBy[it.id].orEmpty()) }
    }

    override suspend fun setInStock(id: Long, inStock: Boolean) =
        update("ingredients", id, buildJsonObject { put("in_stock", inStock) })

    override suspend fun addIngredient(name: String): Ingredient {
        val url = rest.endpoint("rest/v1/ingredients").addQueryParameter("select", IngredientRow.COLUMNS).build()
        val body = buildJsonObject { put("name", name.trim()) }.toRequestBody()
        val rows =
            rest.call({ it.url(url).post(body).header("Prefer", "return=representation") }) { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw rest.rejected(response.code, text, "ingredients")
                json.decodeFromString(ListSerializer(IngredientRow.serializer()), text)
            }
        return rows.single().toIngredient(emptyList(), emptyList())
    }

    override suspend fun removeIngredient(id: Long) = delete("ingredients") { addQueryParameter("id", "eq.$id") }

    /**
     * Delete, then insert: the lists are a few dozen rows and the screen
     * knows the whole answer. Not one transaction — PostgREST cannot — so a
     * failure between the halves leaves the ingredient linked to less, which
     * is a dish wrongly on sale rather than wrongly off it, put right from
     * the same screen.
     */
    override suspend fun setIngredientLinks(id: Long, dishIds: List<Long>, optionIds: List<Long>) {
        coroutineScope {
            val dishes = async { delete("ingredient_items") { addQueryParameter("ingredient_id", "eq.$id") } }
            val options = async { delete("ingredient_options") { addQueryParameter("ingredient_id", "eq.$id") } }
            dishes.await()
            options.await()
        }
        coroutineScope {
            if (dishIds.isNotEmpty()) {
                launch { insert("ingredient_items", links(dishIds) { put("ingredient_id", id); put("item_id", it) }) }
            }
            if (optionIds.isNotEmpty()) {
                launch { insert("ingredient_options", links(optionIds) { put("ingredient_id", id); put("option_id", it) }) }
            }
        }
    }

    // -------------------------------------------------------------------
    // PostgREST
    // -------------------------------------------------------------------

    private suspend fun <T> select(table: String, row: KSerializer<T>, query: HttpUrl.Builder.() -> Unit): List<T> {
        val url = rest.endpoint("rest/v1/$table").apply(query).build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rest.rejected(response.code, body, table)
            json.decodeFromString(ListSerializer(row), body)
        }
    }

    /**
     * One row changed, or an exception. Asked to answer with the row: an
     * update the policies do not allow changes nothing and says nothing, and
     * a switch that quietly does not stick is worse than one that says so.
     */
    private suspend fun update(table: String, id: Long, patch: JsonObject) {
        val url = rest.endpoint("rest/v1/$table").addQueryParameter("id", "eq.$id").addQueryParameter("select", "id").build()
        val changed =
            rest.call({ it.url(url).patch(patch.toRequestBody()).header("Prefer", "return=representation") }) { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw rest.rejected(response.code, text, table)
                json.decodeFromString(ListSerializer(IdRow.serializer()), text)
            }
        if (changed.isEmpty()) throw NotAllowedException()
    }

    /** Rows in; with [onConflict], what is already there is left alone rather than refused. */
    private suspend fun insert(table: String, rows: JsonArray, onConflict: String? = null) {
        val url = rest.endpoint("rest/v1/$table").apply { if (onConflict != null) addQueryParameter("on_conflict", onConflict) }.build()
        val prefer = if (onConflict != null) "return=minimal,resolution=ignore-duplicates" else "return=minimal"
        rest.call({ it.url(url).post(rows.toRequestBody()).header("Prefer", prefer) }) { response ->
            if (!response.isSuccessful) throw rest.rejected(response.code, response.body.string(), table)
        }
    }

    private suspend fun delete(table: String, query: HttpUrl.Builder.() -> Unit) {
        val url = rest.endpoint("rest/v1/$table").apply(query).build()
        rest.call({ it.url(url).delete() }) { response ->
            if (!response.isSuccessful) throw rest.rejected(response.code, response.body.string(), table)
        }
    }

    private fun links(ids: List<Long>, row: JsonObjectBuilder.(Long) -> Unit): JsonArray =
        buildJsonArray { for (id in ids) add(buildJsonObject { row(id) }) }

    @Serializable private class IdRow(val id: Long)

    @Serializable private class NamedRow(val id: Long, val name: String)

    @Serializable
    private class DishRow(
        val id: Long,
        @SerialName("category_id") val categoryId: Long? = null,
        val name: String,
        val description: String? = null,
        val price: Double,
        @SerialName("prep_minutes") val prepMinutes: Int = 0,
        @SerialName("sort_order") val sortOrder: Int = 0,
        val available: Boolean = true,
        @SerialName("image_url") val imageUrl: String? = null,
    ) {
        companion object {
            const val COLUMNS = "id,category_id,name,description,price,prep_minutes,sort_order,available,image_url"
        }
    }

    @Serializable
    private class ItemTagRow(@SerialName("item_id") val itemId: Long, @SerialName("tag_id") val tagId: Long)

    @Serializable
    private class OptionRow(
        val id: Long,
        @SerialName("group_id") val groupId: Long,
        val name: String,
        val price: Double = 0.0,
        val available: Boolean = true,
    )

    @Serializable
    private class GroupLinkRow(@SerialName("item_id") val itemId: Long, @SerialName("group_id") val groupId: Long)

    @Serializable
    private class OptionTagRow(@SerialName("option_id") val optionId: Long, @SerialName("tag_id") val tagId: Long)

    @Serializable
    private class IngredientRow(
        val id: Long,
        val name: String,
        @SerialName("in_stock") val inStock: Boolean = true,
        @SerialName("sort_order") val sortOrder: Int = 0,
    ) {
        fun toIngredient(dishIds: List<Long>, optionIds: List<Long>) = Ingredient(id, name, inStock, sortOrder, dishIds, optionIds)

        companion object {
            const val COLUMNS = "id,name,in_stock,sort_order"
        }
    }

    @Serializable
    private class IngredientItemRow(@SerialName("ingredient_id") val ingredientId: Long, @SerialName("item_id") val itemId: Long)

    @Serializable
    private class IngredientOptionRow(@SerialName("ingredient_id") val ingredientId: Long, @SerialName("option_id") val optionId: Long)

    private companion object {
        /** Public, and world-readable by URL: the customer menu loads it unsigned. */
        const val PHOTO_BUCKET = "menu"

        /** A year: a new photo is a new URL, so nothing stale is ever served. */
        const val PHOTO_CACHE_SECONDS = 31_536_000
    }
}
