package de.hamzabistro.printstation.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The whole menu as staff edit it from the app (android#11): new dishes,
 * archiving, groups of choices, categories and the deal of the day. The
 * database's staff functions do the writing and keep the rules
 * (20261003110000_menu_editing.sql in hamza-bistro-web); what is here is
 * what the forms check first, what they send, and how a refusal reads.
 */

/** A section of the menu: Döner, Getränke. */
data class MenuCategory(
    val id: Long,
    val name: String,
    /** The category's photo on the site; "" for none. */
    val imageUrl: String = "",
    val sortOrder: Int = 0,
)

/**
 * One weekday's deal: a whole category, or one dish in it, [discount] euros
 * cheaper. [day] is 0 = Sunday … 6 = Saturday, as the database counts.
 */
data class MenuDeal(
    val day: Int,
    val categoryId: Long,
    /** The one dish on offer; null when it is the whole category. */
    val itemId: Long?,
    val discount: Double,
)

/** Whether a group asks for exactly one choice (the meat) or any number (extras). */
enum class Selection(val wire: String) {
    SINGLE("single"),
    MULTIPLE("multiple");

    companion object {
        fun of(wire: String?): Selection = entries.find { it.wire == wire } ?: MULTIPLE
    }
}

/** What staff may say about one choice in a group. */
data class OptionEdit(
    val name: String,
    /** What it adds to the dish; 0 for a free choice. */
    val price: Double,
    val additives: List<Int> = emptyList(),
    val available: Boolean = true,
) {
    val valid: Boolean
        get() = name.isNotBlank() && price >= 0 && Additives.allowed(additives)
}

/**
 * Zusatzstoffe, by the numbers the shop's board prints. Which numbers exist
 * is the database's check (`additives <@ array[1..5]`); the words are the app's.
 */
object Additives {
    /** 1 Farbstoff, 2 Konservierungsstoff, 3 Antioxidationsmittel, 4 Geschmacksverstärker, 5 Phosphat. */
    val ALL = listOf(1, 2, 3, 4, 5)

    fun allowed(numbers: List<Int>): Boolean = numbers.all { it in ALL }

    /** Ticks one on or off; kept sorted and without repeats, as the database stores them. */
    fun toggle(numbers: List<Int>, number: Int): List<Int> =
        if (number in numbers) numbers - number else (numbers + number).distinct().sorted()
}

/** Why the database refused a menu edit, by the code it raised. */
enum class MenuEditError(val code: String) {
    /** HB450: the row is gone — deleted or archived elsewhere while the form was open. */
    NOT_FOUND("HB450"),

    /** HB451: the name is taken. Dish names are unique over the whole menu, archived ones included. */
    NAME_TAKEN("HB451"),

    /** HB452: a value the database does not allow; its message names the rule. */
    VALUE_NOT_ALLOWED("HB452"),

    /** HB453: archived — restore it first. */
    ARCHIVED("HB453"),

    /** HB454: a single-choice group on a dish would be left with nothing to choose. */
    GROUP_WOULD_BE_EMPTY("HB454");

    companion object {
        fun of(code: String?): MenuEditError? = entries.find { it.code == code }
    }
}

/** A menu edit the database refused for [reason]; [message] is its own words, in English. */
class MenuEditException(val reason: MenuEditError, message: String) : Exception(message)

/** The pure parts of the editor: parsing, defaults, the order lists and the request bodies. */
object MenuEdits {
    /** Ten minutes past two hours is a typo, not a recipe. As the column allows. */
    const val MAX_PREP = 120

    /** What a new dish gets when its category has none to copy from: the column defaults. */
    const val DEFAULT_PREP = 8

    /**
     * Euros as typed, "7,50" or "7.5"; null when it does not read as an
     * amount. Never more than two decimals: the columns are numeric(…, 2) and
     * a third would be rounded away without a word.
     */
    fun parseEuro(text: String): Double? {
        val trimmed = text.trim().removeSuffix("€").trim().replace(',', '.')
        if (!EURO.matches(trimmed)) return null
        return trimmed.toDoubleOrNull()
    }

    /** "7,50", for a field the form fills in. */
    fun euroText(value: Double): String = "%.2f".format(java.util.Locale.GERMANY, value)

    /**
     * What a new dish starts with: the Selbstabholerrabatt and prep time most
     * of the category's (unarchived) dishes have — what create_menu_item
     * itself fills in when they are left out. On a tie the smaller, as
     * Postgres' mode() takes the first in order.
     */
    fun categoryDefaults(dishes: List<MenuDish>): Pair<Double, Int> {
        val live = dishes.filterNot { it.archived }
        return Pair(mode(live.map { it.pickupDiscount }) ?: 0.0, mode(live.map { it.prepMinutes }) ?: DEFAULT_PREP)
    }

    private fun <T : Comparable<T>> mode(values: List<T>): T? =
        values.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<T, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key

    /**
     * [ids] with [id] moved [by] places (−1 up, +1 down), stopping at either
     * end. What the reorder functions are sent: every row, once each.
     */
    fun move(ids: List<Long>, id: Long, by: Int): List<Long> {
        val from = ids.indexOf(id)
        if (from < 0) return ids
        val to = (from + by).coerceIn(0, ids.lastIndex)
        if (to == from) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The days as the shop's week reads, Monday first; the numbers are the
     * database's, 0 = Sunday.
     */
    val WEEK = listOf(1, 2, 3, 4, 5, 6, 0)

    /**
     * `p_changes` for update_menu_item: only what differs from [before], so
     * a field nobody touched is left as it is — the sold-out switch flipped
     * on another phone meanwhile included. Everything when [before] is null.
     * A blank size goes as JSON null, which clears it.
     */
    fun dishChanges(before: DishEdit?, after: DishEdit): JsonObject =
        buildJsonObject {
            fun differs(old: (DishEdit) -> Any?) = before == null || old(before) != old(after)
            if (after.categoryId != null && differs { it.categoryId }) put("category_id", after.categoryId)
            if (differs { it.name.trim() }) put("name", after.name.trim())
            if (differs { it.description.trim() }) put("description", after.description.trim())
            if (differs { it.price }) put("price", after.price)
            if (differs { it.deposit }) put("deposit", after.deposit)
            if (differs { it.pickupDiscount }) put("pickup_discount", after.pickupDiscount)
            if (differs { it.prepMinutes }) put("prep_minutes", after.prepMinutes)
            if (differs { it.volumeMl }) put("volume_ml", after.volumeMl)
            if (differs { it.additives }) put("additives", numbers(after.additives))
            if (differs { it.imageUrl.trim() }) put("image_url", after.imageUrl.trim())
            if (differs { it.available }) put("available", after.available)
        }

    /**
     * create_menu_item's arguments: the category, name and price on their
     * own, everything else in `p_fields` (which takes no name or category).
     */
    fun createDish(categoryId: Long, edit: DishEdit): JsonObject =
        buildJsonObject {
            put("p_category_id", categoryId)
            put("p_name", edit.name.trim())
            put("p_price", edit.price)
            val all = dishChanges(null, edit)
            put("p_fields", JsonObject(all.filterKeys { it !in CREATE_OWN_ARGUMENTS }))
        }

    /** `p_changes` for update_menu_option, like [dishChanges]. */
    fun optionChanges(before: OptionEdit?, after: OptionEdit): JsonObject =
        buildJsonObject {
            if (before == null || before.name.trim() != after.name.trim()) put("name", after.name.trim())
            if (before == null || before.price != after.price) put("price", after.price)
            if (before == null || before.additives != after.additives) put("additives", numbers(after.additives))
            if (before == null || before.available != after.available) put("available", after.available)
        }

    /** create_menu_option's arguments. */
    fun createOption(groupId: Long, edit: OptionEdit): JsonObject =
        buildJsonObject {
            put("p_group_id", groupId)
            put("p_name", edit.name.trim())
            put("p_price", edit.price)
            put(
                "p_fields",
                buildJsonObject {
                    put("additives", numbers(edit.additives))
                    put("available", edit.available)
                },
            )
        }

    /**
     * set_menu_deal's arguments: one dish when [itemId] is given (the
     * database fills in its category), else the whole [categoryId].
     */
    fun deal(day: Int, discount: Double, categoryId: Long?, itemId: Long?): JsonObject =
        buildJsonObject {
            put("p_day", day)
            put("p_discount", discount)
            put("p_category_id", if (itemId != null) JsonNull else categoryId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_item_id", itemId?.let(::JsonPrimitive) ?: JsonNull)
        }

    /** A deal the form can send: a day of the week, something off, and something to take it off. */
    fun dealValid(day: Int, discount: Double?, categoryId: Long?, itemId: Long?): Boolean =
        day in 0..6 && discount != null && discount > 0 && (categoryId != null || itemId != null)

    fun ids(values: List<Long>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun numbers(values: List<Int>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    /** "12", "7.5", "0.25": no sign, at most two decimals. */
    private val EURO = Regex("""\d+(\.\d{1,2})?""")

    private val CREATE_OWN_ARGUMENTS = setOf("category_id", "name", "price")
}

/**
 * A dish's form as typed: text until it is saved, so "7," on the way to
 * "7,50" is not refused. The same form makes a new dish and edits one.
 */
data class DishForm(
    val name: String,
    val description: String,
    val price: String,
    /** The Pfand inside the price; blank is none. */
    val deposit: String,
    /** The Selbstabholerrabatt; blank is none. */
    val pickupDiscount: String,
    val prepMinutes: String,
    /** Millilitres as typed; blank for food. */
    val volumeMl: String,
    val imageUrl: String,
    val additives: List<Int>,
    val available: Boolean,
    val categoryId: Long?,
    val tags: Set<Long>,
    /** The ticks, in the three states of [Allergens]: null until somebody says. */
    val allergens: List<String>?,
    /** The groups of choices the dish offers, in order. */
    val groupIds: List<Long>,
) {
    private val parsed: Parsed
        get() {
            val bad = mutableSetOf<DishProblem>()
            val price = MenuEdits.parseEuro(price).also { if (it == null) bad += DishProblem.PRICE }
            val deposit = euroOrZero(deposit).also { if (it == null) bad += DishProblem.DEPOSIT }
            val pickup = euroOrZero(pickupDiscount).also { if (it == null) bad += DishProblem.PICKUP_DISCOUNT }
            val prep = prepMinutes.trim().toIntOrNull().also { if (it == null) bad += DishProblem.PREP }
            val volume = DrinkVolume.parse(volumeMl).also { if (it == null) bad += DishProblem.VOLUME }
            val edit =
                DishEdit(
                    name = name,
                    description = description,
                    price = price ?: 0.0,
                    prepMinutes = prep ?: 0,
                    imageUrl = imageUrl,
                    volumeMl = volume?.ml,
                    deposit = deposit ?: 0.0,
                    pickupDiscount = pickup ?: 0.0,
                    additives = additives,
                    available = available,
                    categoryId = categoryId,
                )
            // Without a price there is nothing to hold the Pfand against yet.
            val derived = edit.problems.filterNot { price == null && it == DishProblem.DEPOSIT }
            return Parsed(edit, bad + derived)
        }

    /** Every field that would be refused, to mark in red; empty when the form can be sent. */
    val problems: Set<DishProblem>
        get() = parsed.problems

    /** What it says, or null while a field does not read or the dish cannot be right. */
    val edit: DishEdit?
        get() = parsed.let { if (it.problems.isEmpty()) it.edit else null }

    private class Parsed(val edit: DishEdit, val problems: Set<DishProblem>)

    companion object {
        private fun euroOrZero(text: String): Double? = if (text.isBlank()) 0.0 else MenuEdits.parseEuro(text)

        private fun euroOrBlank(value: Double): String = if (value == 0.0) "" else MenuEdits.euroText(value)

        fun of(dish: MenuDish) =
            DishForm(
                name = dish.name,
                description = dish.description,
                price = MenuEdits.euroText(dish.price),
                deposit = euroOrBlank(dish.deposit),
                pickupDiscount = euroOrBlank(dish.pickupDiscount),
                prepMinutes = dish.prepMinutes.toString(),
                volumeMl = dish.volumeMl?.toString() ?: "",
                imageUrl = dish.imageUrl,
                additives = dish.additives,
                available = dish.available,
                categoryId = dish.categoryId,
                tags = dish.tags.toSet(),
                allergens = dish.allergens,
                groupIds = dish.groupIds,
            )

        /** A new dish in [categoryId], starting from what most of the category's dishes have. */
        fun new(categoryId: Long, category: List<MenuDish>): DishForm {
            val (pickup, prep) = MenuEdits.categoryDefaults(category)
            return DishForm(
                name = "",
                description = "",
                price = "",
                deposit = "",
                pickupDiscount = euroOrBlank(pickup),
                prepMinutes = prep.toString(),
                volumeMl = "",
                imageUrl = "",
                additives = emptyList(),
                available = true,
                categoryId = categoryId,
                tags = emptySet(),
                allergens = null,
                groupIds = emptyList(),
            )
        }
    }
}

/** A choice's form as typed. [optionId] null is a new choice in [groupId]. */
data class OptionForm(
    val groupId: Long,
    val optionId: Long?,
    val name: String,
    val price: String,
    val additives: List<Int>,
    val available: Boolean,
) {
    /** What it says, or null while the price does not read or the choice cannot be right. A blank price is free. */
    val edit: OptionEdit?
        get() {
            val price = if (price.isBlank()) 0.0 else MenuEdits.parseEuro(price) ?: return null
            return OptionEdit(name, price, additives, available).takeIf { it.valid }
        }

    companion object {
        fun of(groupId: Long, option: MenuOption) =
            OptionForm(groupId, option.id, option.name, if (option.price == 0.0) "" else MenuEdits.euroText(option.price), option.additives, option.available)

        fun new(groupId: Long) = OptionForm(groupId, null, "", "", emptyList(), true)
    }
}

/** One weekday's deal as typed: a category, maybe one dish in it, and the euros off. */
data class DealForm(
    val day: Int,
    val categoryId: Long?,
    val itemId: Long?,
    val discount: String,
) {
    val discountValue: Double?
        get() = MenuEdits.parseEuro(discount)

    val valid: Boolean
        get() = MenuEdits.dealValid(day, discountValue, categoryId, itemId)

    companion object {
        fun of(day: Int, deal: MenuDeal?) =
            DealForm(day, deal?.categoryId, deal?.itemId, deal?.discount?.let(MenuEdits::euroText) ?: "")
    }
}

/** A group's form: [id] null is a new group. [itemIds] are the dishes that offer it. */
data class GroupForm(
    val id: Long?,
    val name: String,
    val selection: Selection,
    val itemIds: List<Long>,
) {
    val valid: Boolean
        get() = name.isNotBlank()

    companion object {
        fun of(group: OptionGroup) = GroupForm(group.id, group.name, group.selection, group.itemIds)

        fun new() = GroupForm(null, "", Selection.SINGLE, emptyList())
    }
}

/** A category's form: [id] null is a new category. */
data class CategoryForm(
    val id: Long?,
    val name: String,
    val imageUrl: String,
) {
    val valid: Boolean
        get() = name.isNotBlank()

    companion object {
        fun of(category: MenuCategory) = CategoryForm(category.id, category.name, category.imageUrl)

        fun new() = CategoryForm(null, "", "")
    }
}
