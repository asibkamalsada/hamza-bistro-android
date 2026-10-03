package de.hamzabistro.printstation.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * What staff say about a dish in the app: everything but its allergens
 * (their own editor), its labels and its place in the category (moved with
 * the arrows). Sent through update_menu_item, only what changed.
 */
data class DishEdit(
    val name: String,
    val description: String,
    /** What the customer is charged, Pfand included: place_order reads this column. */
    val price: Double,
    /** Minutes the kitchen needs for one; 0 for anything uncooked. 0–120, as the column allows. */
    val prepMinutes: Int,
    val imageUrl: String,
    /** A drink's size in millilitres; null for food and a drink not sized. See [DrinkVolume]. */
    val volumeMl: Int? = null,
    /** The Pfand inside [price]: never more than the price, and never discounted. */
    val deposit: Double = 0.0,
    /** What comes off for collection (Selbstabholerrabatt); 0 for drinks. */
    val pickupDiscount: Double = 0.0,
    /** Zusatzstoffe by number, see [Additives]. */
    val additives: List<Int> = emptyList(),
    val available: Boolean = true,
    /** Where it is printed; null leaves it where it is. */
    val categoryId: Long? = null,
) {
    /** What is wrong with it, in the order the form shows the fields; empty when it can be sent. */
    val problems: List<DishProblem>
        get() =
            buildList {
                if (name.trim().length < 2) add(DishProblem.NAME)
                if (!(price >= 0)) add(DishProblem.PRICE)
                if (!(deposit >= 0) || deposit > price) add(DishProblem.DEPOSIT)
                if (!(pickupDiscount >= 0)) add(DishProblem.PICKUP_DISCOUNT)
                if (prepMinutes !in 0..MAX_PREP) add(DishProblem.PREP)
                if (!DrinkVolume.allowed(volumeMl)) add(DishProblem.VOLUME)
                if (!Additives.allowed(additives)) add(DishProblem.ADDITIVES)
            }

    /** What the form refuses before the database does: the rules of the site's form. */
    val valid: Boolean
        get() = problems.isEmpty()

    companion object {
        const val MAX_PREP = MenuEdits.MAX_PREP
    }
}

/** A field of [DishEdit] the database would refuse. */
enum class DishProblem {
    NAME,
    PRICE,
    /** Negative, or more than the price: the Pfand is part of the price. */
    DEPOSIT,
    PICKUP_DISCOUNT,
    PREP,
    VOLUME,
    ADDITIVES,
}

/** A dish as staff see it — sold out, or archived, or not. */
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
    /** Allergen letters; [] for "none", null for "not stated yet" — see [Allergens]. */
    val allergens: List<String>? = null,
    /** A drink's size in millilitres, null when it has none. */
    val volumeMl: Int? = null,
    /** The Pfand inside [price]. */
    val deposit: Double = 0.0,
    val categoryId: Long? = null,
    val pickupDiscount: Double = 0.0,
    val additives: List<Int> = emptyList(),
    /** Off the menu for good until restored; customers do not see it at all. */
    val archived: Boolean = false,
    /** The groups of choices it offers, in the order it offers them. */
    val groupIds: List<Long> = emptyList(),
) {
    val edit: DishEdit
        get() = DishEdit(name, description, price, prepMinutes, imageUrl, volumeMl, deposit, pickupDiscount, additives, available, categoryId)

    /** "0,33 l · 6,52 €/l", or null for anything without a size. */
    val unitPrice: String?
        get() = DrinkVolume.line(volumeMl, price, deposit)
}

/** One choice inside a group, with its own sold-out switch. */
data class MenuOption(
    val id: Long,
    val name: String,
    val price: Double,
    val available: Boolean,
    val tags: List<Long>,
    /** What the choice brings onto the dish — extra cheese brings g. Null until stated. */
    val allergens: List<String>? = null,
    val additives: List<Int> = emptyList(),
    /** Gone from every dish that offers its group, until restored. */
    val archived: Boolean = false,
) {
    val edit: OptionEdit
        get() = OptionEdit(name, price, additives, available)
}

/**
 * A group of choices. Groups are shared between dishes — "Dein Fleisch"
 * hangs off every Döner — so switching Chicken off switches it off in
 * every dish at once, and [dishes] says which.
 */
data class OptionGroup(
    val id: Long,
    val name: String,
    /** Every choice in it, archived ones included, in its order. */
    val options: List<MenuOption>,
    /** The dishes that offer it, by name, sorted. */
    val dishes: List<String>,
    val selection: Selection = Selection.MULTIPLE,
    val archived: Boolean = false,
    /** The dishes that offer it, by id. */
    val itemIds: List<Long> = emptyList(),
) {
    /** The choices still in it. */
    val live: List<MenuOption>
        get() = options.filterNot { it.archived }

    /**
     * What a customer can meet: on a dish and with something in it. A group
     * nobody offers, or one with nothing in it, is still being built — the
     * sold-out switches leave it out, the editor does not.
     */
    val offered: Boolean
        get() = !archived && live.isNotEmpty() && dishes.isNotEmpty()
}

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
    /** Every dish in menu order, sold out or not; with [archived], only the archived ones. */
    suspend fun dishes(archived: Boolean = false): List<MenuDish>

    /** The labels that exist, in the order the menu shows them. */
    suspend fun tags(): List<MenuTag>

    /** Takes a dish off the menu, or puts it back. */
    suspend fun setDishAvailable(id: Long, available: Boolean)

    /** Changes what a dish is and what it costs: what differs from [before], nothing else. */
    suspend fun saveDish(id: Long, before: DishEdit, after: DishEdit)

    /** Makes the dish carry exactly these tags. */
    suspend fun setDishTags(id: Long, tags: List<Long>)

    /** The 14 allergens, in the menu's order. */
    suspend fun allergens(): List<Allergen>

    /**
     * States a dish's allergens: the letters, [] for "none", or null to put
     * it back to "not stated". What the database stored, sorted.
     */
    suspend fun setDishAllergens(id: Long, codes: List<String>?): List<String>?

    /** The same for a choice, on every dish that offers it. */
    suspend fun setOptionAllergens(id: Long, codes: List<String>?): List<String>?

    /** Puts a shrunk photo in the bucket; the public URL to save onto the dish. */
    suspend fun uploadPhoto(dishId: Long, photo: Photo, now: Long): String

    /** The same for a category's photo. */
    suspend fun uploadCategoryPhoto(categoryId: Long, photo: Photo, now: Long): String

    /** Every group, archived and unfinished ones included; [OptionGroup.offered] says which customers meet. */
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

    // Editing the menu itself (android#11). Each refusal of the database's
    // own is a [MenuEditException].

    /** A new dish, last in its category; its id. */
    suspend fun createDish(categoryId: Long, edit: DishEdit): Long

    /** Off the menu and out of sight, until [restoreDish]. Past orders keep it. */
    suspend fun archiveDish(id: Long)

    /** Back on the menu, on sale. */
    suspend fun restoreDish(id: Long)

    /** The archived dish called [name], if there is one: what a taken name may be waiting in. */
    suspend fun archivedDishNamed(name: String): MenuDish?

    /** The category's dishes in this order: every one not archived, once each. */
    suspend fun reorderDishes(categoryId: Long, ids: List<Long>)

    /** The groups the dish offers, in this order; [] for none. */
    suspend fun setDishGroups(id: Long, groupIds: List<Long>)

    suspend fun createGroup(name: String, selection: Selection): Long

    /** A null leaves that part as it is. */
    suspend fun updateGroup(id: Long, name: String?, selection: Selection?)

    /** Off every dish and out of the list until restored; how many dishes it was on. */
    suspend fun archiveGroup(id: Long): Int

    /** Back in the list, on no dish. */
    suspend fun restoreGroup(id: Long)

    /** Which dishes offer the group; a dish that gains it gets it last. */
    suspend fun setGroupDishes(id: Long, itemIds: List<Long>)

    /** A new choice, last in its group; its id. */
    suspend fun createOption(groupId: Long, edit: OptionEdit): Long

    suspend fun saveOption(id: Long, before: OptionEdit, after: OptionEdit)

    suspend fun archiveOption(id: Long)

    suspend fun restoreOption(id: Long)

    /** The group's choices in this order: every one not archived, once each. */
    suspend fun reorderOptions(groupId: Long, ids: List<Long>)

    /** Every category, in the menu's order. */
    suspend fun categories(): List<MenuCategory>

    /** A new category, last; the site shows it once it has a dish. Its id. */
    suspend fun createCategory(name: String): Long

    /** A null leaves that part as it is; an [imageUrl] of "" removes the photo. */
    suspend fun updateCategory(id: Long, name: String?, imageUrl: String?)

    /** Every category in this order, once each. */
    suspend fun reorderCategories(ids: List<Long>)

    /** The week's deals, a row for each day that has one. */
    suspend fun deals(): List<MenuDeal>

    /** That day's deal: one dish when [itemId] is given, else the whole [categoryId]. */
    suspend fun setDeal(day: Int, discount: Double, categoryId: Long?, itemId: Long?)

    /** No deal that day. */
    suspend fun clearDeal(day: Int)
}

/** [MenuBackend] over PostgREST and Storage, as the signed-in account. */
class SupabaseMenuBackend internal constructor(private val rest: SupabaseRest) : MenuBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun dishes(archived: Boolean): List<MenuDish> = coroutineScope {
        val categories = async {
            select("menu_categories", NamedRow.serializer()) {
                addQueryParameter("select", "id,name")
                addQueryParameter("order", "sort_order")
            }
        }
        val items = async {
            select("menu_items", DishRow.serializer()) {
                addQueryParameter("select", DishRow.COLUMNS)
                addQueryParameter("archived_at", if (archived) "not.is.null" else "is.null")
                addQueryParameter("order", "sort_order,name")
            }
        }
        val tags = async { select("menu_item_tags", ItemTagRow.serializer()) { addQueryParameter("select", "item_id,tag_id") } }
        val links = async { groupLinks() }
        val tagged = tags.await().groupBy({ it.itemId }, { it.tagId })
        val groupsOf = links.await().groupBy({ it.itemId }, { it.groupId })
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
                        allergens = it.allergens,
                        volumeMl = it.volumeMl,
                        deposit = it.deposit,
                        categoryId = category.id,
                        pickupDiscount = it.pickupDiscount,
                        additives = it.additives,
                        archived = it.archivedAt != null,
                        groupIds = groupsOf[it.id].orEmpty(),
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

    override suspend fun saveDish(id: Long, before: DishEdit, after: DishEdit) {
        val changes = MenuEdits.dishChanges(before, after)
        if (changes.isEmpty()) return
        staff(
            "update_menu_item",
            buildJsonObject {
                put("p_item_id", id)
                put("p_changes", changes)
            },
        )
    }

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

    override suspend fun allergens(): List<Allergen> =
        select("allergens", Allergen.serializer()) {
            addQueryParameter("select", "code,name_de,name_en")
            addQueryParameter("order", "sort_order")
        }

    override suspend fun setDishAllergens(id: Long, codes: List<String>?): List<String>? =
        setAllergens("set_item_allergens", "p_item_id", id, codes)

    override suspend fun setOptionAllergens(id: Long, codes: List<String>?): List<String>? =
        setAllergens("set_option_allergens", "p_option_id", id, codes)

    /**
     * Through the staff functions rather than a PATCH: one call with one
     * answer for the site and the app, and a plain refusal for anyone else.
     * A null is sent as JSON null — that is the "not stated" — never left out.
     */
    private suspend fun setAllergens(function: String, idName: String, id: Long, codes: List<String>?): List<String>? {
        val args = buildJsonObject {
            put(idName, id)
            put("p_codes", codes?.let { list -> buildJsonArray { list.forEach { add(JsonPrimitive(it)) } } } ?: JsonNull)
        }
        val answer =
            try {
                rest.rpc(function, args)
            } catch (e: BackendException) {
                if (e.code == UNKNOWN_ALLERGEN) throw UnknownAllergenException(e.message ?: function)
                throw e
            }
        return json.decodeFromString(STORED, answer.ifBlank { "null" })
    }

    /**
     * Named after the dish and the moment, never reused: a new photo is a new
     * URL, so the object can be cached for a year and the bucket needs no
     * update or delete right.
     */
    override suspend fun uploadPhoto(dishId: Long, photo: Photo, now: Long): String = upload("item-$dishId", photo, now)

    override suspend fun uploadCategoryPhoto(categoryId: Long, photo: Photo, now: Long): String = upload("category-$categoryId", photo, now)

    private suspend fun upload(folder: String, photo: Photo, now: Long): String {
        val path = "$folder/$now.${photo.extension}"
        val url = rest.endpoint("storage/v1/object/$PHOTO_BUCKET/$path").build()
        val body = photo.bytes.toRequestBody(photo.contentType.toMediaType())
        rest.call({ it.url(url).post(body).header("cache-control", "max-age=$PHOTO_CACHE_SECONDS").header("x-upsert", "false") }) { response ->
            if (!response.isSuccessful) throw rest.rejected(response.code, response.body.string(), "photo")
        }
        return rest.endpoint("storage/v1/object/public/$PHOTO_BUCKET/$path").build().toString()
    }

    override suspend fun optionGroups(): List<OptionGroup> = coroutineScope {
        val groups = async {
            select("menu_option_groups", GroupRow.serializer()) {
                addQueryParameter("select", "id,name,selection,archived_at")
                addQueryParameter("order", "name")
            }
        }
        val options = async {
            select("menu_options", OptionRow.serializer()) {
                addQueryParameter("select", "id,group_id,name,price,available,allergens,additives,archived_at")
                addQueryParameter("order", "sort_order,id")
            }
        }
        val links = async { groupLinks() }
        val items = async { select("menu_items", NamedRow.serializer()) { addQueryParameter("select", "id,name") } }
        val tags = async { select("menu_option_tags", OptionTagRow.serializer()) { addQueryParameter("select", "option_id,tag_id") } }

        val names = items.await().associate { it.id to it.name }
        val itemsBy = links.await().groupBy({ it.groupId }, { it.itemId })
        val tagged = tags.await().groupBy({ it.optionId }, { it.tagId })
        val rows = options.await()
        groups.await().map { group ->
            val itemIds = itemsBy[group.id].orEmpty()
            OptionGroup(
                id = group.id,
                name = group.name,
                options =
                    rows.filter { it.groupId == group.id }
                        .map {
                            MenuOption(it.id, it.name, it.price, it.available, tagged[it.id].orEmpty(), it.allergens, it.additives, it.archivedAt != null)
                        },
                dishes = itemIds.mapNotNull { names[it] }.sorted(),
                selection = Selection.of(group.selection),
                archived = group.archivedAt != null,
                itemIds = itemIds,
            )
        }
    }

    /** Which groups each dish offers, in the dish's order. */
    private suspend fun groupLinks(): List<GroupLinkRow> =
        select("menu_item_option_groups", GroupLinkRow.serializer()) {
            addQueryParameter("select", "item_id,group_id")
            addQueryParameter("order", "item_id,sort_order")
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
    // Editing the menu: the staff functions of 20261003110000_menu_editing.sql
    // -------------------------------------------------------------------

    override suspend fun createDish(categoryId: Long, edit: DishEdit): Long =
        newId(staff("create_menu_item", MenuEdits.createDish(categoryId, edit)))

    override suspend fun archiveDish(id: Long) {
        staff("archive_menu_item", buildJsonObject { put("p_item_id", id) })
    }

    override suspend fun restoreDish(id: Long) {
        staff(
            "restore_menu_item",
            buildJsonObject {
                put("p_item_id", id)
                put("p_available", true)
            },
        )
    }

    override suspend fun archivedDishNamed(name: String): MenuDish? {
        val rows =
            select("menu_items", DishRow.serializer()) {
                addQueryParameter("select", DishRow.COLUMNS)
                addQueryParameter("name", "eq.${name.trim()}")
                addQueryParameter("archived_at", "not.is.null")
            }
        val row = rows.firstOrNull() ?: return null
        return MenuDish(
            id = row.id,
            category = "",
            name = row.name,
            description = row.description ?: "",
            price = row.price,
            prepMinutes = row.prepMinutes,
            sortOrder = row.sortOrder,
            imageUrl = row.imageUrl ?: "",
            available = row.available,
            tags = emptyList(),
            allergens = row.allergens,
            volumeMl = row.volumeMl,
            deposit = row.deposit,
            categoryId = row.categoryId,
            pickupDiscount = row.pickupDiscount,
            additives = row.additives,
            archived = true,
        )
    }

    override suspend fun reorderDishes(categoryId: Long, ids: List<Long>) {
        staff(
            "reorder_menu_items",
            buildJsonObject {
                put("p_category_id", categoryId)
                put("p_item_ids", MenuEdits.ids(ids))
            },
        )
    }

    override suspend fun setDishGroups(id: Long, groupIds: List<Long>) {
        staff(
            "set_item_option_groups",
            buildJsonObject {
                put("p_item_id", id)
                put("p_group_ids", MenuEdits.ids(groupIds))
            },
        )
    }

    override suspend fun createGroup(name: String, selection: Selection): Long =
        newId(
            staff(
                "create_option_group",
                buildJsonObject {
                    put("p_name", name.trim())
                    put("p_selection", selection.wire)
                },
            )
        )

    override suspend fun updateGroup(id: Long, name: String?, selection: Selection?) {
        staff(
            "update_option_group",
            buildJsonObject {
                put("p_group_id", id)
                put("p_name", name?.trim())
                put("p_selection", selection?.wire)
            },
        )
    }

    override suspend fun archiveGroup(id: Long): Int =
        staff("archive_option_group", buildJsonObject { put("p_group_id", id) }).trim().toIntOrNull() ?: 0

    override suspend fun restoreGroup(id: Long) {
        staff("restore_option_group", buildJsonObject { put("p_group_id", id) })
    }

    override suspend fun setGroupDishes(id: Long, itemIds: List<Long>) {
        staff(
            "set_option_group_items",
            buildJsonObject {
                put("p_group_id", id)
                put("p_item_ids", MenuEdits.ids(itemIds))
            },
        )
    }

    override suspend fun createOption(groupId: Long, edit: OptionEdit): Long =
        newId(staff("create_menu_option", MenuEdits.createOption(groupId, edit)))

    override suspend fun saveOption(id: Long, before: OptionEdit, after: OptionEdit) {
        val changes = MenuEdits.optionChanges(before, after)
        if (changes.isEmpty()) return
        staff(
            "update_menu_option",
            buildJsonObject {
                put("p_option_id", id)
                put("p_changes", changes)
            },
        )
    }

    override suspend fun archiveOption(id: Long) {
        staff("archive_menu_option", buildJsonObject { put("p_option_id", id) })
    }

    override suspend fun restoreOption(id: Long) {
        staff(
            "restore_menu_option",
            buildJsonObject {
                put("p_option_id", id)
                put("p_available", true)
            },
        )
    }

    override suspend fun reorderOptions(groupId: Long, ids: List<Long>) {
        staff(
            "reorder_menu_options",
            buildJsonObject {
                put("p_group_id", groupId)
                put("p_option_ids", MenuEdits.ids(ids))
            },
        )
    }

    override suspend fun categories(): List<MenuCategory> =
        select("menu_categories", CategoryRow.serializer()) {
            addQueryParameter("select", "id,name,image_url,sort_order")
            addQueryParameter("order", "sort_order,name")
        }.map { MenuCategory(it.id, it.name, it.imageUrl ?: "", it.sortOrder) }

    override suspend fun createCategory(name: String): Long =
        newId(staff("create_menu_category", buildJsonObject { put("p_name", name.trim()) }))

    override suspend fun updateCategory(id: Long, name: String?, imageUrl: String?) {
        staff(
            "update_menu_category",
            buildJsonObject {
                put("p_category_id", id)
                put("p_name", name?.trim())
                put("p_image_url", imageUrl?.trim())
            },
        )
    }

    override suspend fun reorderCategories(ids: List<Long>) {
        staff("reorder_menu_categories", buildJsonObject { put("p_category_ids", MenuEdits.ids(ids)) })
    }

    override suspend fun deals(): List<MenuDeal> =
        select("menu_deals", DealRow.serializer()) {
            addQueryParameter("select", "day_of_week,category_id,item_id,discount")
            addQueryParameter("order", "day_of_week")
        }.map { MenuDeal(it.day, it.categoryId, it.itemId, it.discount) }

    override suspend fun setDeal(day: Int, discount: Double, categoryId: Long?, itemId: Long?) {
        staff("set_menu_deal", MenuEdits.deal(day, discount, categoryId, itemId))
    }

    override suspend fun clearDeal(day: Int) {
        staff("clear_menu_deal", buildJsonObject { put("p_day", day) })
    }

    /** A staff function, with its HB45x refusals said as a [MenuEditException]. */
    private suspend fun staff(function: String, args: JsonObject): String =
        try {
            rest.rpc(function, args)
        } catch (e: BackendException) {
            val reason = MenuEditError.of(e.code) ?: throw e
            throw MenuEditException(reason, e.message ?: function)
        }

    /** The id a create_* function answers with. */
    private fun newId(answer: String): Long =
        answer.trim().toLongOrNull() ?: throw BackendException(200, "no id in the answer: $answer")

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
        val allergens: List<String>? = null,
        @SerialName("volume_ml") val volumeMl: Int? = null,
        val deposit: Double = 0.0,
        @SerialName("pickup_discount") val pickupDiscount: Double = 0.0,
        val additives: List<Int> = emptyList(),
        @SerialName("archived_at") val archivedAt: String? = null,
    ) {
        companion object {
            const val COLUMNS =
                "id,category_id,name,description,price,prep_minutes,sort_order,available,image_url,allergens,volume_ml,deposit," +
                    "pickup_discount,additives,archived_at"
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
        val allergens: List<String>? = null,
        val additives: List<Int> = emptyList(),
        @SerialName("archived_at") val archivedAt: String? = null,
    )

    @Serializable
    private class GroupRow(
        val id: Long,
        val name: String,
        val selection: String? = null,
        @SerialName("archived_at") val archivedAt: String? = null,
    )

    @Serializable
    private class CategoryRow(
        val id: Long,
        val name: String,
        @SerialName("image_url") val imageUrl: String? = null,
        @SerialName("sort_order") val sortOrder: Int = 0,
    )

    @Serializable
    private class DealRow(
        @SerialName("day_of_week") val day: Int,
        @SerialName("category_id") val categoryId: Long,
        @SerialName("item_id") val itemId: Long? = null,
        val discount: Double,
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
        /** What set_*_allergens raises for a letter public.allergens does not have. */
        const val UNKNOWN_ALLERGEN = "HB433"

        /** Their answer: the stored array, or null. */
        val STORED = ListSerializer(String.serializer()).nullable

        /** Public, and world-readable by URL: the customer menu loads it unsigned. */
        const val PHOTO_BUCKET = "menu"

        /** A year: a new photo is a new URL, so nothing stale is ever served. */
        const val PHOTO_CACHE_SECONDS = 31_536_000
    }
}
