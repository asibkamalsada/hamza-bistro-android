package de.hamzabistro.printstation.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Allergen
import de.hamzabistro.printstation.core.Allergens
import de.hamzabistro.printstation.core.DishEdit
import de.hamzabistro.printstation.core.DrinkVolume
import de.hamzabistro.printstation.core.Ingredient
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.MenuOption
import de.hamzabistro.printstation.core.MenuTag
import de.hamzabistro.printstation.core.OptionGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three things that can be sold out, in the order they are reached for. */
enum class MenuTab {
    DISHES,
    OPTIONS,
    INGREDIENTS,
}

/** A dish's form as typed: text until it is saved, so "7," on the way to "7,50" is not refused. */
data class DishForm(
    val name: String,
    val description: String,
    val price: String,
    val prepMinutes: String,
    val sortOrder: String,
    val imageUrl: String,
    /** Millilitres as typed; blank for food. */
    val volumeMl: String,
    val tags: Set<Long>,
    /** The ticks, in the three states of [Allergens]: null until somebody says. */
    val allergens: List<String>?,
) {
    /** What it says, or null while a number does not read as one or the edit cannot be right. */
    val edit: DishEdit?
        get() {
            val price = price.trim().replace(',', '.').toDoubleOrNull() ?: return null
            val prep = prepMinutes.trim().toIntOrNull() ?: return null
            val sort = sortOrder.trim().toIntOrNull() ?: return null
            val volume = DrinkVolume.parse(volumeMl) ?: return null
            return DishEdit(name, description, price, prep, sort, imageUrl, volume.ml).takeIf { it.valid }
        }

    companion object {
        fun of(dish: MenuDish) =
            DishForm(
                name = dish.name,
                description = dish.description,
                price = "%.2f".format(java.util.Locale.GERMANY, dish.price),
                prepMinutes = dish.prepMinutes.toString(),
                sortOrder = dish.sortOrder.toString(),
                imageUrl = dish.imageUrl,
                volumeMl = dish.volumeMl?.toString() ?: "",
                tags = dish.tags.toSet(),
                allergens = dish.allergens,
            )
    }
}

data class MenuState(
    val tab: MenuTab = MenuTab.DISHES,
    val loading: Boolean = false,
    val error: String? = null,
    val dishes: List<MenuDish> = emptyList(),
    val tags: List<MenuTag> = emptyList(),
    val groups: List<OptionGroup> = emptyList(),
    val ingredients: List<Ingredient> = emptyList(),
    /** What is being written, as "d4", "o12", "i3": dish, option, ingredient. */
    val busy: Set<String> = emptySet(),
    /** The dish whose form is open; one at a time. */
    val editing: Long? = null,
    val form: DishForm? = null,
    val uploading: Boolean = false,
    /** A photo landed in the bucket and waits for "Save". */
    val photoReady: Boolean = false,
    /** The dish just saved, to say so. */
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
)

/**
 * What is on the menu today — the site's /menu-admin. The sold-out switches
 * are the everyday job and stay one tap; editing a dish and saying what an
 * ingredient is used in are behind a second. Each tab reads fresh whenever
 * it is opened: this screen's whole job is being up to date.
 */
class MenuViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph
    private val menu = graph.menu

    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()

    private fun fail(error: String) = _state.update { it.copy(error = error) }

    fun open(tab: MenuTab) {
        _state.update { it.copy(tab = tab, error = null, saved = null, editing = null, form = null, linking = null, allergenOption = null) }
        load()
    }

    fun load() {
        val tab = _state.value.tab
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            attempt(app, ::fail) {
                coroutineScope {
                    when (tab) {
                        MenuTab.DISHES -> {
                            val tags = async { menu.tags() }
                            val allergens = async { menu.allergens() }
                            val dishes = menu.dishes()
                            _state.update { it.copy(dishes = dishes, tags = tags.await(), allergens = allergens.await(), error = null) }
                        }
                        MenuTab.OPTIONS -> {
                            val tags = async { menu.tags() }
                            val allergens = async { menu.allergens() }
                            val groups = menu.optionGroups()
                            _state.update { it.copy(groups = groups, tags = tags.await(), allergens = allergens.await(), error = null) }
                        }
                        MenuTab.INGREDIENTS -> {
                            val dishes = async { menu.dishes() }
                            val groups = async { menu.optionGroups() }
                            val ingredients = menu.ingredients()
                            _state.update { it.copy(ingredients = ingredients, dishes = dishes.await(), groups = groups.await(), error = null) }
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
        _state.update { it.copy(editing = dish.id, form = DishForm.of(dish), error = null, saved = null, photoReady = false) }

    fun cancelEdit() = _state.update { it.copy(editing = null, form = null) }

    fun updateForm(change: (DishForm) -> DishForm) = _state.update { it.copy(form = it.form?.let(change)) }

    fun toggleFormTag(tag: MenuTag) = updateForm { form -> form.copy(tags = if (tag.id in form.tags) form.tags - tag.id else form.tags + tag.id) }

    fun toggleFormAllergen(code: String) = updateForm { it.copy(allergens = Allergens.toggle(it.allergens, code)) }

    fun toggleFormAllergenNone() = updateForm { it.copy(allergens = Allergens.toggleNone(it.allergens)) }

    fun toggleOnlyMissingAllergens() = _state.update { it.copy(onlyMissingAllergens = !it.onlyMissingAllergens) }

    /**
     * The dish first, then its tags, and only if the dish saved: a refused
     * price is the thing worth saying, and tags on a dish whose edit did not
     * land would leave the two halves disagreeing. Read again after, since
     * the database trims and rounds, and a new position moves the dish.
     * Allergens last, and only when the ticks differ from what was read:
     * an untouched form sends nothing, so it never claims "none".
     */
    fun saveDish() {
        val id = _state.value.editing ?: return
        val form = _state.value.form ?: return
        val edit = form.edit
        if (edit == null) {
            fail(app.getString(R.string.menu_form_invalid))
            return
        }
        val allergens = Allergens.toSave(_state.value.dishes.find { it.id == id }?.allergens, form.allergens)
        val key = "d$id"
        _state.update { it.copy(busy = it.busy + key, error = null) }
        viewModelScope.launch {
            val done =
                attempt(app, ::fail) {
                    menu.saveDish(id, edit)
                    menu.setDishTags(id, form.tags.toList())
                    if (allergens != null) menu.setDishAllergens(id, allergens.codes)
                    menu.dishes()
                }
            _state.update {
                if (done == null) it.copy(busy = it.busy - key)
                else it.copy(busy = it.busy - key, dishes = done, editing = null, form = null, saved = edit.name.trim())
            }
        }
    }

    /**
     * Shrinks the picked photo and puts it in the bucket, then fills in the
     * URL — it does not save: the dish changes when the form is saved, which
     * leaves room to pick another first. A form given up on leaves a small
     * file nothing points at; the bucket has no delete right to spare.
     */
    fun pickPhoto(uri: Uri) {
        val id = _state.value.editing ?: return
        _state.update { it.copy(uploading = true, photoReady = false, error = null) }
        viewModelScope.launch {
            val url =
                attempt(app, ::fail) {
                    val photo = withContext(Dispatchers.Default) { shrinkPhoto(app.contentResolver, uri) }
                    if (photo == null) {
                        fail(app.getString(R.string.photo_unreadable))
                        null
                    } else {
                        menu.uploadPhoto(id, photo, System.currentTimeMillis())
                    }
                }
            _state.update {
                if (url == null || it.editing != id) it.copy(uploading = false)
                else it.copy(uploading = false, photoReady = true, form = it.form?.copy(imageUrl = url))
            }
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
        _state.update { it.copy(allergenOption = option.id, allergenDraft = option.allergens, error = null) }

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
                actions = { TextButton(onClick = menu::load, enabled = !state.loading) { Text(stringResource(R.string.reload)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
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
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
            }
            state.error?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error) } }
            state.saved?.let { name -> item(key = "saved") { Text(stringResource(R.string.menu_saved, name), fontWeight = FontWeight.SemiBold) } }
            if (state.loading) item(key = "loading") { Text(stringResource(R.string.please_wait), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when (state.tab) {
                MenuTab.DISHES -> dishes(state, menu)
                MenuTab.OPTIONS -> options(state, menu)
                MenuTab.INGREDIENTS -> ingredients(state, menu)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

/** One row with a sold-out switch: the name, whether it is on, and anything else beside it. */
@Composable
private fun SwitchLine(
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
private fun AllergenLine(allergens: List<String>?) {
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
private fun MissingAllergens(count: Int, only: Boolean, onToggle: () -> Unit) {
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
private fun AllergenPicker(
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
private fun Tick(checked: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
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
// Dishes
// -----------------------------------------------------------------------

private fun LazyListScope.dishes(state: MenuState, menu: MenuViewModel) {
    item(key = "dishes-intro") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint(stringResource(R.string.menu_intro))
            Hint(stringResource(R.string.menu_dashboard_note))
            Text(stringResource(R.string.menu_sold_out_count, state.dishes.count { !it.available }), fontWeight = FontWeight.SemiBold)
            MissingAllergens(Allergens.missing(state.dishes.map { it.allergens }), state.onlyMissingAllergens, menu::toggleOnlyMissingAllergens)
        }
    }
    val shown = if (state.onlyMissingAllergens) state.dishes.filter { it.allergens == null } else state.dishes
    for ((category, dishes) in shown.groupBy { it.category }) {
        item(key = "category-$category") { Heading(category) }
        items(dishes, key = { "d${it.id}" }) { dish ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val editing = state.editing == dish.id
                    SwitchLine(
                        name = dish.name,
                        on = dish.available,
                        onText = "${Format.euro(dish.price)} · ${stringResource(R.string.menu_available)}",
                        offText = "${Format.euro(dish.price)} · ${stringResource(R.string.menu_sold_out)}",
                        busy = "d${dish.id}" in state.busy,
                        onToggle = { menu.toggleDish(dish) },
                        extra = {
                            TextButton(onClick = { if (editing) menu.cancelEdit() else menu.startEdit(dish) }) {
                                Text(stringResource(if (editing) R.string.cancel else R.string.menu_edit))
                            }
                        },
                    )
                    dish.unitPrice?.let { Hint(it) }
                    AllergenLine(dish.allergens)
                    val form = state.form
                    if (editing && form != null) DishFormView(dish, form, state, menu)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DishFormView(dish: MenuDish, form: DishForm, state: MenuState, menu: MenuViewModel) {
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(menu::pickPhoto) }
    HorizontalDivider()
    OutlinedTextField(
        value = form.name,
        onValueChange = { v -> menu.updateForm { it.copy(name = v) } },
        label = { Text(stringResource(R.string.menu_name)) },
        isError = form.name.trim().length < 2,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.description,
        onValueChange = { v -> menu.updateForm { it.copy(description = v) } },
        label = { Text(stringResource(R.string.menu_description)) },
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(form.price, R.string.menu_price, KeyboardType.Decimal, Modifier.weight(1f)) { v -> menu.updateForm { it.copy(price = v) } }
        NumberField(form.prepMinutes, R.string.menu_prep, KeyboardType.Number, Modifier.weight(1f)) { v -> menu.updateForm { it.copy(prepMinutes = v) } }
        NumberField(form.sortOrder, R.string.menu_position, KeyboardType.Number, Modifier.weight(1f)) { v -> menu.updateForm { it.copy(sortOrder = v) } }
    }
    Hint(stringResource(R.string.menu_prep_hint))

    // A drink's size, for the "0,33 l · 6,52 €/l" under it on the menu. Blank for food.
    NumberField(form.volumeMl, R.string.menu_volume, KeyboardType.Number, Modifier.fillMaxWidth()) { v -> menu.updateForm { it.copy(volumeMl = v) } }
    Hint(stringResource(R.string.menu_volume_hint))

    // Labels, not categories: a tick leaves the dish where it is printed and adds a way to find it.
    if (state.tags.isNotEmpty()) {
        Text(stringResource(R.string.menu_tags), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tag in state.tags) {
                FilterChip(selected = tag.id in form.tags, onClick = { menu.toggleFormTag(tag) }, label = { Text(tag.name) })
            }
        }
    }

    if (state.allergens.isNotEmpty()) {
        AllergenPicker(state.allergens, form.allergens, enabled = true, onToggle = menu::toggleFormAllergen, onNone = menu::toggleFormAllergenNone)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PhotoPreview(form.imageUrl, menu)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !state.uploading,
            ) {
                Text(stringResource(R.string.menu_photo_pick))
            }
            Hint(
                stringResource(
                    when {
                        state.uploading -> R.string.menu_photo_uploading
                        state.photoReady -> R.string.menu_photo_ready
                        else -> R.string.menu_photo_hint
                    }
                )
            )
        }
    }
    OutlinedTextField(
        value = form.imageUrl,
        onValueChange = { v -> menu.updateForm { it.copy(imageUrl = v) } },
        label = { Text(stringResource(R.string.menu_photo_url)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = menu::cancelEdit) { Text(stringResource(R.string.cancel)) }
        val busy = "d${dish.id}" in state.busy
        Button(onClick = menu::saveDish, enabled = !busy && !state.uploading && form.edit != null) {
            Text(stringResource(if (busy) R.string.please_wait else R.string.save))
        }
    }
}

@Composable
private fun NumberField(value: String, label: Int, keyboard: KeyboardType, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

/** The square the menu draws, at the size it draws it, so what is shown here is what customers get. */
@Composable
private fun PhotoPreview(url: String, menu: MenuViewModel) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        // Not on every keystroke in the URL field: once it has stood still for a moment.
        delay(PREVIEW_SETTLE_MS)
        image = menu.preview(url)
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = stringResource(R.string.menu_photo_preview),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
}

// -----------------------------------------------------------------------
// Options
// -----------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.options(state: MenuState, menu: MenuViewModel) {
    item(key = "options-intro") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint(stringResource(R.string.menu_options_intro))
            Hint(stringResource(R.string.menu_options_shared))
            val out = state.groups.sumOf { group -> group.options.count { !it.available } }
            Text(stringResource(R.string.menu_sold_out_count, out), fontWeight = FontWeight.SemiBold)
            MissingAllergens(
                Allergens.missing(state.groups.flatMap { group -> group.options.map { it.allergens } }),
                state.onlyMissingAllergens,
                menu::toggleOnlyMissingAllergens,
            )
        }
    }
    // The database allows some tags on a choice — the vegan leaf — and refuses others.
    val tags = state.tags.filter { it.onOptions }
    val shown =
        if (!state.onlyMissingAllergens) state.groups
        else state.groups.map { g -> g.copy(options = g.options.filter { it.allergens == null }) }.filter { it.options.isNotEmpty() }
    for (group in shown) {
        item(key = "group-${group.id}") {
            Column {
                Heading(group.name)
                // Said first: it is the difference between chicken off one Döner and off all four.
                Hint(stringResource(R.string.menu_option_used_in, group.dishes.joinToString(", ")))
            }
        }
        items(group.options, key = { "g${group.id}-o${it.id}" }) { option ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val busy = "o${option.id}" in state.busy
                    val editingAllergens = state.allergenOption == option.id
                    SwitchLine(
                        name = option.name,
                        on = option.available,
                        onText = stringResource(R.string.menu_available),
                        offText = stringResource(R.string.menu_sold_out),
                        busy = busy,
                        onToggle = { menu.toggleOption(option) },
                        extra = {
                            if (state.allergens.isNotEmpty()) {
                                TextButton(onClick = { if (editingAllergens) menu.cancelOptionAllergens() else menu.startOptionAllergens(option) }) {
                                    Text(stringResource(if (editingAllergens) R.string.cancel else R.string.menu_allergens))
                                }
                            }
                        },
                    )
                    AllergenLine(option.allergens)
                    if (tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (tag in tags) {
                                FilterChip(
                                    selected = tag.id in option.tags,
                                    enabled = !busy,
                                    onClick = { menu.toggleOptionTag(option, tag) },
                                    label = { Text(tag.name) },
                                )
                            }
                        }
                    }
                    if (editingAllergens) {
                        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider()
                            Hint(stringResource(R.string.menu_allergens_option_shared))
                            AllergenPicker(
                                state.allergens,
                                state.allergenDraft,
                                enabled = !busy,
                                onToggle = menu::toggleOptionAllergen,
                                onNone = menu::toggleOptionAllergenNone,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = menu::cancelOptionAllergens) { Text(stringResource(R.string.cancel)) }
                                Button(onClick = menu::saveOptionAllergens, enabled = !busy) {
                                    Text(stringResource(if (busy) R.string.please_wait else R.string.save))
                                }
                            }
                        }
                    }
                }
            }
        }
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
                for (group in state.groups) {
                    Hint(group.name)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in group.options) {
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

private const val PREVIEW_SETTLE_MS = 400L
