package de.hamzabistro.printstation.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Additives
import de.hamzabistro.printstation.core.Allergen
import de.hamzabistro.printstation.core.Allergens
import de.hamzabistro.printstation.core.CategoryForm
import de.hamzabistro.printstation.core.DealForm
import de.hamzabistro.printstation.core.DishForm
import de.hamzabistro.printstation.core.GroupForm
import de.hamzabistro.printstation.core.Ingredient
import de.hamzabistro.printstation.core.MenuCategory
import de.hamzabistro.printstation.core.MenuDeal
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.MenuEditError
import de.hamzabistro.printstation.core.MenuEditException
import de.hamzabistro.printstation.core.MenuEdits
import de.hamzabistro.printstation.core.MenuOption
import de.hamzabistro.printstation.core.MenuTag
import de.hamzabistro.printstation.core.OptionForm
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The three things that can be sold out, in the order they are reached for,
 * then what the menu is made of: its categories and the deal of the day.
 */
enum class MenuTab {
    DISHES,
    OPTIONS,
    INGREDIENTS,
    CATEGORIES,
    DEALS,
}

/** Something that takes a dish or choice off the menu, asked about before it is done. */
sealed interface Confirm {
    data class ArchiveDish(val dish: MenuDish) : Confirm

    data class ArchiveGroup(val group: OptionGroup) : Confirm

    data class ArchiveOption(val option: MenuOption) : Confirm
}

data class MenuState(
    val tab: MenuTab = MenuTab.DISHES,
    val loading: Boolean = false,
    val error: String? = null,
    val dishes: List<MenuDish> = emptyList(),
    val tags: List<MenuTag> = emptyList(),
    val groups: List<OptionGroup> = emptyList(),
    val ingredients: List<Ingredient> = emptyList(),
    val categories: List<MenuCategory> = emptyList(),
    val deals: List<MenuDeal> = emptyList(),
    /** What is being written, as "d4", "o12", "i3", "g2", "c1", "w3": dish, option, ingredient, group, category, weekday. */
    val busy: Set<String> = emptySet(),
    /** The dish whose form is open; one at a time. */
    val editing: Long? = null,
    /** The category a new dish is being made in, while [editing] is null. */
    val creatingIn: Long? = null,
    val form: DishForm? = null,
    val uploading: Boolean = false,
    /** A photo landed in the bucket and waits for "Save". */
    val photoReady: Boolean = false,
    /** What was just done, to say so. */
    val saved: String? = null,
    /** The ingredient whose links are open; one at a time. */
    val linking: Long? = null,
    val linkDishes: Set<Long> = emptySet(),
    val linkOptions: Set<Long> = emptySet(),
    /** The 14, for the ticks; empty until read. */
    val allergens: List<Allergen> = emptyList(),
    /** Only the dishes or choices nobody has stated allergens for: to work through them one by one. */
    val onlyMissingAllergens: Boolean = false,
    /** The choice whose allergens are open; one at a time. */
    val allergenOption: Long? = null,
    val allergenDraft: List<String>? = null,
    /** The "Archiviert" filter: what was taken off the menu, to bring back. */
    val showArchived: Boolean = false,
    val archivedDishes: List<MenuDish> = emptyList(),
    /** The arrows to put dishes, choices or categories in order. */
    val arranging: Boolean = false,
    val confirm: Confirm? = null,
    /** An archived dish with the name just refused as taken (HB451), to offer back. */
    val restoreOffer: MenuDish? = null,
    val groupForm: GroupForm? = null,
    val optionForm: OptionForm? = null,
    val categoryForm: CategoryForm? = null,
    val dealForm: DealForm? = null,
)

/**
 * What is on the menu today — the site's /menu-admin. The sold-out switches
 * are the everyday job and stay one tap; editing, adding and archiving are
 * behind a second, and archiving asks first. Each tab reads fresh whenever
 * it is opened: this screen's whole job is being up to date.
 */
class MenuViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph
    private val menu = graph.menu

    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()

    private fun fail(error: String) = _state.update { it.copy(error = error) }

    private fun closeForms(s: MenuState) =
        s.copy(
            editing = null,
            creatingIn = null,
            form = null,
            linking = null,
            allergenOption = null,
            groupForm = null,
            optionForm = null,
            categoryForm = null,
            dealForm = null,
            restoreOffer = null,
        )

    fun open(tab: MenuTab) {
        _state.update { closeForms(it).copy(tab = tab, error = null, saved = null, arranging = false) }
        load()
    }

    /** Reads the open tab again; with [keepError], what just went wrong stays said. */
    fun load(keepError: Boolean = false) {
        val tab = _state.value.tab
        val archived = _state.value.showArchived
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            attempt(app, ::fail) {
                coroutineScope {
                    when (tab) {
                        MenuTab.DISHES -> {
                            val tags = async { menu.tags() }
                            val allergens = async { menu.allergens() }
                            val groups = async { menu.optionGroups() }
                            val categories = async { menu.categories() }
                            val gone = async { if (archived) menu.dishes(archived = true) else emptyList() }
                            val dishes = menu.dishes()
                            _state.update {
                                it.copy(
                                    dishes = dishes,
                                    tags = tags.await(),
                                    allergens = allergens.await(),
                                    groups = groups.await(),
                                    categories = categories.await(),
                                    archivedDishes = gone.await(),
                                    error = it.error.takeIf { keepError },
                                )
                            }
                        }
                        MenuTab.OPTIONS -> {
                            val tags = async { menu.tags() }
                            val allergens = async { menu.allergens() }
                            val dishes = async { menu.dishes() }
                            val groups = menu.optionGroups()
                            _state.update { it.copy(groups = groups, dishes = dishes.await(), tags = tags.await(), allergens = allergens.await(), error = it.error.takeIf { keepError }) }
                        }
                        MenuTab.INGREDIENTS -> {
                            val dishes = async { menu.dishes() }
                            val groups = async { menu.optionGroups() }
                            val ingredients = menu.ingredients()
                            _state.update { it.copy(ingredients = ingredients, dishes = dishes.await(), groups = groups.await(), error = it.error.takeIf { keepError }) }
                        }
                        MenuTab.CATEGORIES -> {
                            val dishes = async { menu.dishes() }
                            val categories = menu.categories()
                            _state.update { it.copy(categories = categories, dishes = dishes.await(), error = it.error.takeIf { keepError }) }
                        }
                        MenuTab.DEALS -> {
                            val dishes = async { menu.dishes() }
                            val categories = async { menu.categories() }
                            val deals = menu.deals()
                            _state.update { it.copy(deals = deals, categories = categories.await(), dishes = dishes.await(), error = it.error.takeIf { keepError }) }
                        }
                    }
                }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    /**
     * A switch flipped at once, so it feels immediate, and put back if the
     * write is refused.
     */
    private fun flip(key: String, apply: (Boolean) -> Unit, to: Boolean, write: suspend () -> Unit) {
        if (key in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + key, error = null, saved = null) }
        apply(to)
        viewModelScope.launch {
            if (attempt(app, ::fail) { write() } == null) apply(!to)
            _state.update { it.copy(busy = it.busy - key) }
        }
    }

    /**
     * One write under [key], the row's buttons greyed out meanwhile. On a
     * taken dish name ([dishName], HB451) it looks for an archived dish of
     * that name, to offer it back rather than leave the owner guessing.
     */
    private fun write(
        key: String,
        dishName: String? = null,
        onFailure: () -> Unit = {},
        block: suspend () -> Unit,
        done: (MenuState) -> MenuState = { it },
    ) {
        if (key in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + key, error = null, saved = null) }
        viewModelScope.launch {
            var refused: Exception? = null
            val ok = attempt(app, { message -> fail(message) }) {
                try {
                    block()
                } catch (e: MenuEditException) {
                    refused = e
                    throw e
                }
            } != null
            if (!ok && dishName != null && (refused as? MenuEditException)?.reason == MenuEditError.NAME_TAKEN) {
                attempt(app, {}) { menu.archivedDishNamed(dishName) }?.let { found -> _state.update { it.copy(restoreOffer = found) } }
            }
            _state.update { s -> (if (ok) done(s) else s).copy(busy = s.busy - key) }
            if (!ok) onFailure()
        }
    }

    /** Every open form closed, nothing saved. */
    fun cancelForms() = _state.update { closeForms(it) }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun dismissRestoreOffer() = _state.update { it.copy(restoreOffer = null) }

    fun toggleArranging() = _state.update { closeForms(it).copy(arranging = !it.arranging) }

    fun toggleArchived() {
        _state.update { closeForms(it).copy(showArchived = !it.showArchived, arranging = false) }
        load()
    }

    // -------------------------------------------------------------------
    // Dishes
    // -------------------------------------------------------------------

    fun toggleDish(dish: MenuDish) {
        val to = !dish.available
        flip("d${dish.id}", { on -> _state.update { s -> s.copy(dishes = s.dishes.map { if (it.id == dish.id) it.copy(available = on) else it }) } }, to) {
            menu.setDishAvailable(dish.id, to)
        }
    }

    fun startEdit(dish: MenuDish) =
        _state.update { closeForms(it).copy(editing = dish.id, form = DishForm.of(dish), error = null, saved = null, photoReady = false) }

    /** "+ Gericht" in a category: the same form, empty, with what the category's dishes usually have. */
    fun startNew(categoryId: Long) =
        _state.update { s ->
            closeForms(s).copy(
                creatingIn = categoryId,
                form = DishForm.new(categoryId, s.dishes.filter { it.categoryId == categoryId }),
                error = null,
                saved = null,
                photoReady = false,
            )
        }

    fun cancelEdit() = _state.update { it.copy(editing = null, creatingIn = null, form = null) }

    fun updateForm(change: (DishForm) -> DishForm) = _state.update { it.copy(form = it.form?.let(change)) }

    fun toggleFormTag(tag: MenuTag) = updateForm { form -> form.copy(tags = if (tag.id in form.tags) form.tags - tag.id else form.tags + tag.id) }

    fun toggleFormAllergen(code: String) = updateForm { it.copy(allergens = Allergens.toggle(it.allergens, code)) }

    fun toggleFormAllergenNone() = updateForm { it.copy(allergens = Allergens.toggleNone(it.allergens)) }

    fun toggleFormAdditive(number: Int) = updateForm { it.copy(additives = Additives.toggle(it.additives, number)) }

    fun addFormGroup(id: Long) = updateForm { if (id in it.groupIds) it else it.copy(groupIds = it.groupIds + id) }

    fun removeFormGroup(id: Long) = updateForm { it.copy(groupIds = it.groupIds - id) }

    fun moveFormGroup(id: Long, by: Int) = updateForm { it.copy(groupIds = MenuEdits.move(it.groupIds, id, by)) }

    fun toggleOnlyMissingAllergens() = _state.update { it.copy(onlyMissingAllergens = !it.onlyMissingAllergens) }

    /**
     * The dish first, then its labels, allergens and groups, each only when
     * it differs from what was read, and only if the dish saved: a refused
     * price is the thing worth saying. An untouched allergen form sends
     * nothing, so it never claims "none".
     *
     * A new dish is made first and the form then stays open on it, now an
     * edit: the photo needs the dish's id, and a step that fails after the
     * dish exists is tried again as an edit rather than as a second dish.
     */
    fun saveDish() {
        val state = _state.value
        val form = state.form ?: return
        val edit = form.edit
        if (edit == null) {
            fail(app.getString(R.string.menu_form_invalid))
            return
        }
        val creatingIn = state.creatingIn
        val editingId = state.editing
        if (creatingIn == null && editingId == null) return
        val key = if (creatingIn != null) "new" else "d$editingId"
        var made: Long? = null
        write(
            key,
            dishName = edit.name.trim(),
            // A dish made but not finished: read again, so its form shows and the retry is an edit of it.
            onFailure = { if (made != null) load(keepError = true) },
            block = {
                val original = state.dishes.find { it.id == editingId }
                val id =
                    if (creatingIn != null) {
                        menu.createDish(creatingIn, edit).also { id ->
                            made = id
                            // From here on a retry is an edit of this dish, not a second one.
                            _state.update { it.copy(creatingIn = null, editing = id) }
                        }
                    } else {
                        checkNotNull(editingId).also { menu.saveDish(it, original?.edit ?: edit, edit) }
                    }
                if (form.tags != original?.tags.orEmpty().toSet()) menu.setDishTags(id, form.tags.toList())
                Allergens.toSave(original?.allergens, form.allergens)?.let { menu.setDishAllergens(id, it.codes) }
                if (form.groupIds != original?.groupIds.orEmpty()) menu.setDishGroups(id, form.groupIds)
                val dishes = menu.dishes()
                _state.update { it.copy(dishes = dishes) }
            },
        ) { s ->
            val name = edit.name.trim()
            val dish = made?.let { id -> s.dishes.find { it.id == id } }
            if (dish != null) s.copy(editing = dish.id, form = DishForm.of(dish), saved = app.getString(R.string.menu_dish_created, name))
            else s.copy(editing = null, form = null, saved = app.getString(R.string.menu_saved, name))
        }
    }

    fun askArchiveDish(dish: MenuDish) = _state.update { it.copy(confirm = Confirm.ArchiveDish(dish)) }

    fun archiveDish(dish: MenuDish) {
        _state.update { it.copy(confirm = null) }
        write("d${dish.id}", block = { menu.archiveDish(dish.id) }) { s ->
            closeForms(s).copy(dishes = s.dishes.filterNot { it.id == dish.id }, saved = app.getString(R.string.menu_archived_done, dish.name))
        }
    }

    fun restoreDish(dish: MenuDish) =
        write("d${dish.id}", block = { menu.restoreDish(dish.id); rereadDishes() }) { s ->
            closeForms(s).copy(saved = app.getString(R.string.menu_restored_done, dish.name))
        }

    /** HB451's offer taken: the archived dish comes back instead of a second one. */
    fun restoreOffered() {
        val dish = _state.value.restoreOffer ?: return
        _state.update { closeForms(it) }
        write("d${dish.id}", block = { menu.restoreDish(dish.id); rereadDishes() }) { s ->
            s.copy(saved = app.getString(R.string.menu_restored_done, dish.name))
        }
    }

    /** Both lists, after a dish came back. */
    private suspend fun rereadDishes() = coroutineScope {
        val gone = async { if (_state.value.showArchived) menu.dishes(archived = true) else emptyList() }
        val dishes = menu.dishes()
        _state.update { it.copy(dishes = dishes, archivedDishes = gone.await()) }
    }

    /** One place up or down in its category, at once, and read again if the database says no. */
    fun moveDish(dish: MenuDish, by: Int) {
        val categoryId = dish.categoryId ?: return
        val ids = _state.value.dishes.filter { it.categoryId == categoryId }.map { it.id }
        val order = MenuEdits.move(ids, dish.id, by)
        if (order == ids) return
        _state.update { s ->
            val moved = order.mapNotNull { id -> s.dishes.find { it.id == id } }
            s.copy(dishes = s.dishes.filterNot { it.categoryId == categoryId }.let { rest ->
                // Back where the category was, so the list still reads like the menu.
                val at = s.dishes.indexOfFirst { it.categoryId == categoryId }.coerceAtLeast(0)
                rest.take(at) + moved + rest.drop(at)
            })
        }
        write("c$categoryId", onFailure = { load(keepError = true) }, block = { menu.reorderDishes(categoryId, order) })
    }

    /**
     * Shrinks the picked photo and puts it in the bucket, then fills in the
     * URL — it does not save: the dish changes when the form is saved, which
     * leaves room to pick another first. A form given up on leaves a small
     * file nothing points at; the bucket has no delete right to spare.
     */
    fun pickPhoto(uri: Uri) {
        val id = _state.value.editing ?: return
        upload(uri, { photo -> menu.uploadPhoto(id, photo, System.currentTimeMillis()) }) { s, url ->
            if (s.editing != id) s else s.copy(form = s.form?.copy(imageUrl = url))
        }
    }

    /** The same for a category, whose form must be one already made. */
    fun pickCategoryPhoto(uri: Uri) {
        val id = _state.value.categoryForm?.id ?: return
        upload(uri, { photo -> menu.uploadCategoryPhoto(id, photo, System.currentTimeMillis()) }) { s, url ->
            s.categoryForm?.takeIf { it.id == id }?.let { form -> s.copy(categoryForm = form.copy(imageUrl = url)) } ?: s
        }
    }

    private fun upload(uri: Uri, put: suspend (Photo) -> String, done: (MenuState, String) -> MenuState) {
        _state.update { it.copy(uploading = true, photoReady = false, error = null) }
        viewModelScope.launch {
            val url =
                attempt(app, ::fail) {
                    val photo = withContext(Dispatchers.Default) { shrinkPhoto(app.contentResolver, uri) }
                    if (photo == null) {
                        fail(app.getString(R.string.photo_unreadable))
                        null
                    } else {
                        put(photo)
                    }
                }
            _state.update { if (url == null) it.copy(uploading = false) else done(it.copy(uploading = false, photoReady = true), url) }
        }
    }

    /** The photo at [url], for the square beside the form; null when there is none to show. */
    suspend fun preview(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        val bytes = graph.photoPreview(url) ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }

    // -------------------------------------------------------------------
    // Options
    // -------------------------------------------------------------------

    private fun updateOption(id: Long, change: (MenuOption) -> MenuOption) =
        _state.update { s -> s.copy(groups = s.groups.map { g -> g.copy(options = g.options.map { if (it.id == id) change(it) else it }) }) }

    /** Off in every dish that offers it: the chicken runs out once, not once per Döner. */
    fun toggleOption(option: MenuOption) {
        val to = !option.available
        flip("o${option.id}", { on -> updateOption(option.id) { it.copy(available = on) } }, to) { menu.setOptionAvailable(option.id, to) }
    }

    fun toggleOptionTag(option: MenuOption, tag: MenuTag) {
        val on = tag.id !in option.tags
        flip("o${option.id}", { add -> updateOption(option.id) { it.copy(tags = if (add) it.tags + tag.id else it.tags - tag.id) } }, on) {
            menu.setOptionTag(option.id, tag.id, on)
        }
    }

    fun startOptionAllergens(option: MenuOption) =
        _state.update { closeForms(it).copy(allergenOption = option.id, allergenDraft = option.allergens, error = null) }

    fun cancelOptionAllergens() = _state.update { it.copy(allergenOption = null) }

    fun toggleOptionAllergen(code: String) = _state.update { it.copy(allergenDraft = Allergens.toggle(it.allergenDraft, code)) }

    fun toggleOptionAllergenNone() = _state.update { it.copy(allergenDraft = Allergens.toggleNone(it.allergenDraft)) }

    /**
     * Once for the choice, which every dish offering it shares — the
     * Kräutersauce is one row. Nothing is sent when the ticks are what was
     * read; the row shows what the database stored, sorted.
     */
    fun saveOptionAllergens() {
        val state = _state.value
        val id = state.allergenOption ?: return
        val stored = state.groups.firstNotNullOfOrNull { g -> g.options.find { it.id == id } }?.allergens
        val change = Allergens.toSave(stored, state.allergenDraft)
        if (change == null) {
            cancelOptionAllergens()
            return
        }
        val key = "o$id"
        if (key in state.busy) return
        _state.update { it.copy(busy = it.busy + key, error = null) }
        viewModelScope.launch {
            // Wrapped, because null is an answer here ("not stated") and attempt's null is a failure.
            val saved = attempt(app, ::fail) { Allergens.Change(menu.setOptionAllergens(id, change.codes)) }
            if (saved != null) updateOption(id) { it.copy(allergens = saved.codes) }
            _state.update { s ->
                if (saved == null) s.copy(busy = s.busy - key)
                else s.copy(busy = s.busy - key, allergenOption = s.allergenOption.takeIf { it != id })
            }
        }
    }

    /** After a structural change: the groups as the database now has them. */
    private suspend fun rereadGroups() {
        val groups = menu.optionGroups()
        _state.update { it.copy(groups = groups) }
    }

    fun startGroup(group: OptionGroup?) =
        _state.update { closeForms(it).copy(groupForm = group?.let(GroupForm::of) ?: GroupForm.new(), error = null, saved = null) }

    fun updateGroupForm(change: (GroupForm) -> GroupForm) = _state.update { it.copy(groupForm = it.groupForm?.let(change)) }

    fun toggleGroupDish(id: Long) = updateGroupForm { f -> f.copy(itemIds = if (id in f.itemIds) f.itemIds - id else f.itemIds + id) }

    /**
     * The name and kind, then which dishes offer it, each only if it
     * changed. A new group is made with no dish: a single-choice group needs
     * a choice before any dish may offer it, so the choice form opens next.
     */
    fun saveGroup() {
        val form = _state.value.groupForm ?: return
        if (!form.valid) return
        val before = _state.value.groups.find { it.id == form.id }
        var made: Long? = null
        write(
            "g${form.id ?: "new"}",
            block = {
                val id = form.id
                if (id == null) {
                    made = menu.createGroup(form.name, form.selection)
                } else {
                    val name = form.name.trim().takeIf { it != before?.name }
                    val selection = form.selection.takeIf { it != before?.selection }
                    if (name != null || selection != null) menu.updateGroup(id, name, selection)
                    if (form.itemIds.toSet() != before?.itemIds.orEmpty().toSet()) menu.setGroupDishes(id, form.itemIds)
                }
                rereadGroups()
            },
        ) { s ->
            val id = made
            if (id != null) s.copy(groupForm = null, optionForm = OptionForm.new(id), saved = app.getString(R.string.menu_group_created, form.name.trim()))
            else s.copy(groupForm = null, saved = app.getString(R.string.menu_saved, form.name.trim()))
        }
    }

    fun askArchiveGroup(group: OptionGroup) = _state.update { it.copy(confirm = Confirm.ArchiveGroup(group)) }

    fun archiveGroup(group: OptionGroup) {
        _state.update { it.copy(confirm = null) }
        write("g${group.id}", block = { menu.archiveGroup(group.id); rereadGroups() }) { s ->
            closeForms(s).copy(saved = app.getString(R.string.menu_archived_done, group.name))
        }
    }

    fun restoreGroup(group: OptionGroup) =
        write("g${group.id}", block = { menu.restoreGroup(group.id); rereadGroups() }) { s ->
            s.copy(saved = app.getString(R.string.menu_restored_done, group.name))
        }

    fun startOption(groupId: Long, option: MenuOption?) =
        _state.update {
            closeForms(it).copy(optionForm = option?.let { o -> OptionForm.of(groupId, o) } ?: OptionForm.new(groupId), error = null, saved = null)
        }

    fun updateOptionForm(change: (OptionForm) -> OptionForm) = _state.update { it.copy(optionForm = it.optionForm?.let(change)) }

    fun saveOption() {
        val form = _state.value.optionForm ?: return
        val edit = form.edit ?: return
        val before = _state.value.groups.flatMap { it.options }.find { it.id == form.optionId }
        write(
            "o${form.optionId ?: "new"}",
            block = {
                val id = form.optionId
                if (id == null) menu.createOption(form.groupId, edit) else menu.saveOption(id, before?.edit ?: edit, edit)
                rereadGroups()
            },
        ) { s -> s.copy(optionForm = null, saved = app.getString(R.string.menu_saved, edit.name.trim())) }
    }

    fun askArchiveOption(option: MenuOption) = _state.update { it.copy(confirm = Confirm.ArchiveOption(option)) }

    fun archiveOption(option: MenuOption) {
        _state.update { it.copy(confirm = null) }
        write("o${option.id}", block = { menu.archiveOption(option.id); rereadGroups() }) { s ->
            closeForms(s).copy(saved = app.getString(R.string.menu_archived_done, option.name))
        }
    }

    fun restoreOption(option: MenuOption) =
        write("o${option.id}", block = { menu.restoreOption(option.id); rereadGroups() }) { s ->
            s.copy(saved = app.getString(R.string.menu_restored_done, option.name))
        }

    fun moveOption(group: OptionGroup, option: MenuOption, by: Int) {
        val ids = group.live.map { it.id }
        val order = MenuEdits.move(ids, option.id, by)
        if (order == ids) return
        _state.update { s ->
            s.copy(groups = s.groups.map { g ->
                if (g.id != group.id) g
                else g.copy(options = order.mapNotNull { id -> g.options.find { it.id == id } } + g.options.filter { it.archived })
            })
        }
        write("g${group.id}", onFailure = { load(keepError = true) }, block = { menu.reorderOptions(group.id, order) })
    }

    // -------------------------------------------------------------------
    // Ingredients
    // -------------------------------------------------------------------

    fun toggleIngredient(ingredient: Ingredient) {
        val to = !ingredient.inStock
        flip(
            "i${ingredient.id}",
            { on -> _state.update { s -> s.copy(ingredients = s.ingredients.map { if (it.id == ingredient.id) it.copy(inStock = on) else it }) } },
            to,
        ) {
            menu.setInStock(ingredient.id, to)
        }
    }

    /** Straight into its links: an ingredient used in nothing does nothing. */
    fun addIngredient(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            attempt(app, ::fail) { menu.addIngredient(name) }?.let { added ->
                _state.update { it.copy(ingredients = it.ingredients + added, error = null) }
                startLinks(added)
            }
        }
    }

    fun removeIngredient(ingredient: Ingredient) {
        val key = "i${ingredient.id}"
        _state.update { it.copy(busy = it.busy + key, error = null) }
        viewModelScope.launch {
            val gone = attempt(app, ::fail) { menu.removeIngredient(ingredient.id) } != null
            _state.update { s ->
                if (!gone) s.copy(busy = s.busy - key)
                else
                    s.copy(
                        busy = s.busy - key,
                        ingredients = s.ingredients.filterNot { it.id == ingredient.id },
                        linking = s.linking.takeIf { it != ingredient.id },
                    )
            }
        }
    }

    fun startLinks(ingredient: Ingredient) =
        _state.update { it.copy(linking = ingredient.id, linkDishes = ingredient.dishIds.toSet(), linkOptions = ingredient.optionIds.toSet(), error = null) }

    fun cancelLinks() = _state.update { it.copy(linking = null) }

    fun toggleLinkDish(id: Long) = _state.update { it.copy(linkDishes = if (id in it.linkDishes) it.linkDishes - id else it.linkDishes + id) }

    fun toggleLinkOption(id: Long) = _state.update { it.copy(linkOptions = if (id in it.linkOptions) it.linkOptions - id else it.linkOptions + id) }

    fun saveLinks() {
        val state = _state.value
        val id = state.linking ?: return
        val dishes = state.linkDishes.toList()
        val options = state.linkOptions.toList()
        val key = "i$id"
        _state.update { it.copy(busy = it.busy + key, error = null) }
        viewModelScope.launch {
            val saved = attempt(app, ::fail) { menu.setIngredientLinks(id, dishes, options) } != null
            _state.update { s ->
                if (!saved) s.copy(busy = s.busy - key)
                else
                    s.copy(
                        busy = s.busy - key,
                        linking = null,
                        ingredients = s.ingredients.map { if (it.id == id) it.copy(dishIds = dishes, optionIds = options) else it },
                    )
            }
        }
    }

    // -------------------------------------------------------------------
    // Categories
    // -------------------------------------------------------------------

    fun startCategory(category: MenuCategory?) =
        _state.update { closeForms(it).copy(categoryForm = category?.let(CategoryForm::of) ?: CategoryForm.new(), error = null, saved = null, photoReady = false) }

    fun updateCategoryForm(change: (CategoryForm) -> CategoryForm) = _state.update { it.copy(categoryForm = it.categoryForm?.let(change)) }

    fun saveCategory() {
        val form = _state.value.categoryForm ?: return
        if (!form.valid) return
        val before = _state.value.categories.find { it.id == form.id }
        write(
            "c${form.id ?: "new"}",
            block = {
                val id = form.id
                if (id == null) {
                    menu.createCategory(form.name)
                } else {
                    val name = form.name.trim().takeIf { it != before?.name }
                    val image = form.imageUrl.trim().takeIf { it != before?.imageUrl }
                    if (name != null || image != null) menu.updateCategory(id, name, image)
                }
                val categories = menu.categories()
                _state.update { it.copy(categories = categories) }
            },
        ) { s -> s.copy(categoryForm = null, saved = app.getString(R.string.menu_saved, form.name.trim())) }
    }

    fun moveCategory(category: MenuCategory, by: Int) {
        val ids = _state.value.categories.map { it.id }
        val order = MenuEdits.move(ids, category.id, by)
        if (order == ids) return
        _state.update { s -> s.copy(categories = order.mapNotNull { id -> s.categories.find { it.id == id } }) }
        write("categories", onFailure = { load(keepError = true) }, block = { menu.reorderCategories(order) })
    }

    // -------------------------------------------------------------------
    // Angebote
    // -------------------------------------------------------------------

    fun startDeal(day: Int) =
        _state.update { s -> closeForms(s).copy(dealForm = DealForm.of(day, s.deals.find { it.day == day }), error = null, saved = null) }

    fun updateDealForm(change: (DealForm) -> DealForm) = _state.update { it.copy(dealForm = it.dealForm?.let(change)) }

    fun saveDeal() {
        val form = _state.value.dealForm ?: return
        val discount = form.discountValue ?: return
        if (!form.valid) return
        write(
            "w${form.day}",
            block = {
                menu.setDeal(form.day, discount, form.categoryId, form.itemId)
                val deals = menu.deals()
                _state.update { it.copy(deals = deals) }
            },
        ) { s -> s.copy(dealForm = null) }
    }

    fun clearDeal(day: Int) =
        write(
            "w$day",
            block = {
                menu.clearDeal(day)
                val deals = menu.deals()
                _state.update { it.copy(deals = deals) }
            },
        ) { s -> s.copy(dealForm = null) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MenuScreen(onBack: () -> Unit) {
    val menu: MenuViewModel = viewModel()
    val state by menu.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { menu.open(state.tab) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = { TextButton(onClick = { menu.load() }, enabled = !state.loading) { Text(stringResource(R.string.reload)) } },
            )
        },
    ) { padding ->
        // On the tablet the forms stay a readable width, in the middle.
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "tabs") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tab in MenuTab.entries) {
                            FilterChip(
                                selected = state.tab == tab,
                                onClick = { menu.open(tab) },
                                label = {
                                    Text(
                                        stringResource(
                                            when (tab) {
                                                MenuTab.DISHES -> R.string.menu_tab_dishes
                                                MenuTab.OPTIONS -> R.string.menu_tab_options
                                                MenuTab.INGREDIENTS -> R.string.menu_tab_ingredients
                                                MenuTab.CATEGORIES -> R.string.menu_tab_categories
                                                MenuTab.DEALS -> R.string.menu_tab_deals
                                            }
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
                state.error?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error) } }
                state.saved?.let { done -> item(key = "saved") { Text(done, fontWeight = FontWeight.SemiBold) } }
                if (state.loading) item(key = "loading") { Text(stringResource(R.string.please_wait), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                when (state.tab) {
                    MenuTab.DISHES -> dishes(state, menu)
                    MenuTab.OPTIONS -> options(state, menu)
                    MenuTab.INGREDIENTS -> ingredients(state, menu)
                    MenuTab.CATEGORIES -> categories(state, menu)
                    MenuTab.DEALS -> deals(state, menu)
                }
            }
        }
    }
    MenuDialogs(state, menu)
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

/** One row with a sold-out switch: the name, whether it is on, and anything else beside it. */
@Composable
internal fun SwitchLine(
    name: String,
    on: Boolean,
    onText: String,
    offText: String,
    busy: Boolean,
    onToggle: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (on) onText else offText,
                style = MaterialTheme.typography.bodySmall,
                color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        extra()
        Switch(checked = on, enabled = !busy, onCheckedChange = { onToggle() })
    }
}

// -----------------------------------------------------------------------
// Allergens
// -----------------------------------------------------------------------

/** On every row: the letters, "none", or in red that nobody has said yet — what the menu shows as "Angaben folgen". */
@Composable
internal fun AllergenLine(allergens: List<String>?) {
    when {
        allergens == null ->
            Text(stringResource(R.string.menu_allergens_not_stated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        allergens.isEmpty() -> Hint(stringResource(R.string.menu_allergens_none))
        else -> Hint(stringResource(R.string.menu_allergens_list, allergens.joinToString(", ")))
    }
}

/** How many rows are still "not stated", and a switch to show only those, to work through them on the tablet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MissingAllergens(count: Int, only: Boolean, onToggle: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.menu_allergens_missing_count, count),
            fontWeight = FontWeight.SemiBold,
            color = if (count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        FilterChip(selected = only, onClick = onToggle, label = { Text(stringResource(R.string.menu_allergens_missing_only)) })
    }
}

/**
 * The 14 ticks and a separate "Keines der 14", like the site's picker: see
 * [Allergens] for why nothing ticked is "not stated" and not "none".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AllergenPicker(
    all: List<Allergen>,
    value: List<String>?,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onNone: () -> Unit,
) {
    val german = LocalConfiguration.current.locales[0].language == "de"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.menu_allergens), style = MaterialTheme.typography.labelLarge)
        Hint(stringResource(R.string.menu_allergens_hint))
        FlowRow {
            for (allergen in all) {
                Tick(checked = value?.contains(allergen.code) == true, enabled = enabled, onToggle = { onToggle(allergen.code) }, modifier = Modifier.width(260.dp)) {
                    Text(allergen.code, fontWeight = FontWeight.Bold)
                    Text(" " + allergen.name(german), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Tick(checked = value?.isEmpty() == true, enabled = enabled, onToggle = onNone) {
            Text(stringResource(R.string.menu_allergens_none_box), fontWeight = FontWeight.SemiBold)
        }
        if (value == null) {
            Text(stringResource(R.string.menu_allergens_not_stated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A checkbox with its label, the whole row a target for a finger. */
@Composable
internal fun Tick(checked: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        label()
    }
}

// -----------------------------------------------------------------------
// Ingredients
// -----------------------------------------------------------------------

private fun LazyListScope.ingredients(state: MenuState, menu: MenuViewModel) {
    item(key = "ingredients-intro") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint(stringResource(R.string.menu_ingredients_intro))
            Text(stringResource(R.string.menu_ingredients_out_count, state.ingredients.count { !it.inStock }), fontWeight = FontWeight.SemiBold)
            if (state.ingredients.isEmpty() && !state.loading) Hint(stringResource(R.string.menu_ingredients_empty))
        }
    }
    items(state.ingredients, key = { "i${it.id}" }) { ingredient ->
        IngredientCard(ingredient, state, menu)
    }
    item(key = "ingredient-add") {
        var name by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text(stringResource(R.string.menu_ingredient_new)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    menu.addIngredient(name)
                    name = ""
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.menu_ingredient_add))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IngredientCard(ingredient: Ingredient, state: MenuState, menu: MenuViewModel) {
    var confirming by remember { mutableStateOf(false) }
    val busy = "i${ingredient.id}" in state.busy
    val linking = state.linking == ingredient.id
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchLine(
                name = ingredient.name,
                on = ingredient.inStock,
                onText = stringResource(R.string.menu_ingredient_in),
                offText = stringResource(R.string.menu_ingredient_out),
                busy = busy,
                onToggle = { menu.toggleIngredient(ingredient) },
                extra = {
                    TextButton(onClick = { if (linking) menu.cancelLinks() else menu.startLinks(ingredient) }) {
                        Text(stringResource(R.string.menu_ingredient_links_count, ingredient.dishIds.size, ingredient.optionIds.size))
                    }
                },
            )
            if (ingredient.dishIds.isEmpty() && ingredient.optionIds.isEmpty()) {
                Text(stringResource(R.string.menu_ingredient_unused), style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.SOON))
            }
            if (linking) {
                HorizontalDivider()
                Text(stringResource(R.string.menu_ingredient_links), style = MaterialTheme.typography.titleSmall)
                // Dishes first and in menu order: "no pizza cheese, no pizza" is a whole category, and reads as one.
                Text(stringResource(R.string.menu_tab_dishes), style = MaterialTheme.typography.labelLarge)
                for ((category, dishes) in state.dishes.groupBy { it.category }) {
                    Hint(category)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (dish in dishes) {
                            FilterChip(selected = dish.id in state.linkDishes, onClick = { menu.toggleLinkDish(dish.id) }, label = { Text(dish.name) })
                        }
                    }
                }
                Text(stringResource(R.string.menu_tab_options), style = MaterialTheme.typography.labelLarge)
                for (group in state.groups.filterNot { it.archived }) {
                    Hint(group.name)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in group.live) {
                            FilterChip(selected = option.id in state.linkOptions, onClick = { menu.toggleLinkOption(option.id) }, label = { Text(option.name) })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirming = true }, enabled = !busy) {
                        Text(stringResource(R.string.menu_ingredient_delete), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = menu::cancelLinks) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = menu::saveLinks, enabled = !busy) {
                        Text(stringResource(if (busy) R.string.please_wait else R.string.save))
                    }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            text = { Text(stringResource(R.string.menu_ingredient_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        menu.removeIngredient(ingredient)
                    }
                ) {
                    Text(stringResource(R.string.menu_ingredient_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal const val PREVIEW_SETTLE_MS = 400L

/** Wide enough for two fields side by side, narrow enough to read on the tablet. */
private val MAX_CONTENT_WIDTH = 960.dp
