package de.hamzabistro.printstation.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Additives
import de.hamzabistro.printstation.core.Allergens
import de.hamzabistro.printstation.core.DishForm
import de.hamzabistro.printstation.core.DishProblem
import de.hamzabistro.printstation.core.MenuDish
import kotlinx.coroutines.delay

// -----------------------------------------------------------------------
// Dishes
// -----------------------------------------------------------------------

/**
 * The dishes by category, every category shown even while it is empty, so
 * "+ Gericht" is there for a new one. Behind "Archiviert" are the dishes
 * taken off the menu, each with a way back.
 */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.dishes(state: MenuState, menu: MenuViewModel) {
    item(key = "dishes-intro") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint(stringResource(R.string.menu_intro))
            Text(stringResource(R.string.menu_sold_out_count, state.dishes.count { !it.available }), fontWeight = FontWeight.SemiBold)
            MissingAllergens(Allergens.missing(state.dishes.map { it.allergens }), state.onlyMissingAllergens, menu::toggleOnlyMissingAllergens)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.arranging, onClick = menu::toggleArranging, label = { Text(stringResource(R.string.menu_arrange)) })
                FilterChip(selected = state.showArchived, onClick = menu::toggleArchived, label = { Text(stringResource(R.string.menu_archived_filter)) })
            }
        }
    }
    if (state.showArchived) {
        archivedDishes(state, menu)
        return
    }
    val shown = if (state.onlyMissingAllergens) state.dishes.filter { it.allergens == null } else state.dishes
    // The categories in the menu's order; a dish whose category is not among them (read in between) still shows.
    val byCategory = shown.groupBy { it.categoryId }
    val sections = state.categories.map { it.id to it.name } + byCategory.keys.filter { id -> state.categories.none { it.id == id } }.map { id -> id to byCategory[id].orEmpty().firstOrNull()?.category.orEmpty() }
    for ((categoryId, name) in sections) {
        val dishes = byCategory[categoryId].orEmpty()
        if (state.onlyMissingAllergens && dishes.isEmpty()) continue
        item(key = "category-$categoryId") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) { Heading(name) }
                if (categoryId != null && !state.arranging) {
                    TextButton(onClick = { menu.startNew(categoryId) }, enabled = state.creatingIn != categoryId) { Text(stringResource(R.string.menu_dish_new)) }
                }
            }
        }
        if (categoryId != null && state.creatingIn == categoryId) {
            item(key = "new-dish-$categoryId") {
                val form = state.form
                if (form != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.menu_dish_new_title, name), style = MaterialTheme.typography.titleSmall)
                            DishFormView(null, form, state, menu)
                        }
                    }
                }
            }
        }
        if (dishes.isEmpty() && state.creatingIn != categoryId) item(key = "empty-$categoryId") { Hint(stringResource(R.string.menu_category_empty)) }
        items(dishes, key = { "d${it.id}" }) { dish -> DishCard(dish, dishes, state, menu) }
    }
}

@Composable
private fun DishCard(dish: MenuDish, category: List<MenuDish>, state: MenuState, menu: MenuViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val editing = state.editing == dish.id
            val busy = "d${dish.id}" in state.busy
            if (state.arranging) {
                Arrange(
                    name = dish.name,
                    first = category.firstOrNull()?.id == dish.id,
                    last = category.lastOrNull()?.id == dish.id,
                    busy = "c${dish.categoryId}" in state.busy,
                    onMove = { by -> menu.moveDish(dish, by) },
                )
                return@Column
            }
            SwitchLine(
                name = dish.name,
                on = dish.available,
                onText = "${Format.euro(dish.price)} · ${stringResource(R.string.menu_available)}",
                offText = "${Format.euro(dish.price)} · ${stringResource(R.string.menu_sold_out)}",
                busy = busy,
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

/** A row in "Reihenfolge": its name and the two arrows, which are big enough for a thumb. */
@Composable
internal fun Arrange(name: String, first: Boolean, last: Boolean, busy: Boolean, onMove: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onMove(-1) }, enabled = !first && !busy) { Text(stringResource(R.string.menu_move_up)) }
        OutlinedButton(onClick = { onMove(1) }, enabled = !last && !busy, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.menu_move_down)) }
    }
}

private fun LazyListScope.archivedDishes(state: MenuState, menu: MenuViewModel) {
    item(key = "archived-intro") {
        Hint(stringResource(if (state.archivedDishes.isEmpty() && !state.loading) R.string.menu_archived_none else R.string.menu_archived_intro))
    }
    items(state.archivedDishes, key = { "a${it.id}" }) { dish ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(dish.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Hint("${dish.category} · ${Format.euro(dish.price)}")
                }
                Button(onClick = { menu.restoreDish(dish) }, enabled = "d${dish.id}" !in state.busy) { Text(stringResource(R.string.menu_restore)) }
            }
        }
    }
}

/** Two fields side by side on the tablet, one under the other on a phone. */
@Composable
internal fun FieldPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (LocalConfiguration.current.screenWidthDp >= WIDE_DP) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    } else {
        first(Modifier.fillMaxWidth())
        second(Modifier.fillMaxWidth())
    }
}

/**
 * The dish's form, for a new one ([dish] null) and an existing one alike.
 * Each field the database would refuse is marked as it is typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DishFormView(dish: MenuDish?, form: DishForm, state: MenuState, menu: MenuViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(menu::pickPhoto) }
    val problems = form.problems
    var confirmMove by remember { mutableStateOf(false) }
    HorizontalDivider()
    OutlinedTextField(
        value = form.name,
        onValueChange = { v -> menu.updateForm { it.copy(name = v) } },
        label = { Text(stringResource(R.string.menu_name)) },
        isError = DishProblem.NAME in problems,
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
    FieldPair(
        { m -> NumberField(form.price, R.string.menu_price, KeyboardType.Decimal, m, DishProblem.PRICE in problems) { v -> menu.updateForm { it.copy(price = v) } } },
        { m -> NumberField(form.deposit, R.string.menu_deposit, KeyboardType.Decimal, m, DishProblem.DEPOSIT in problems) { v -> menu.updateForm { it.copy(deposit = v) } } },
    )
    Hint(stringResource(R.string.menu_deposit_hint))
    FieldPair(
        { m ->
            NumberField(form.pickupDiscount, R.string.menu_pickup_discount, KeyboardType.Decimal, m, DishProblem.PICKUP_DISCOUNT in problems) { v ->
                menu.updateForm { it.copy(pickupDiscount = v) }
            }
        },
        { m -> NumberField(form.prepMinutes, R.string.menu_prep, KeyboardType.Number, m, DishProblem.PREP in problems) { v -> menu.updateForm { it.copy(prepMinutes = v) } } },
    )
    Hint(stringResource(R.string.menu_prep_hint))

    // A drink's size, for the "0,33 l · 6,52 €/l" under it on the menu. Blank for food.
    NumberField(form.volumeMl, R.string.menu_volume, KeyboardType.Number, Modifier.fillMaxWidth(), DishProblem.VOLUME in problems) { v ->
        menu.updateForm { it.copy(volumeMl = v) }
    }
    Hint(stringResource(R.string.menu_volume_hint))

    AdditivePicker(form.additives, menu::toggleFormAdditive)

    // Only an existing dish moves: a new one is made where "+ Gericht" was tapped.
    if (dish != null && state.categories.size > 1) {
        Text(stringResource(R.string.menu_category), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (category in state.categories) {
                FilterChip(selected = form.categoryId == category.id, onClick = { menu.updateForm { it.copy(categoryId = category.id) } }, label = { Text(category.name) })
            }
        }
    }

    // Labels, not categories: a tick leaves the dish where it is printed and adds a way to find it.
    if (state.tags.isNotEmpty()) {
        Text(stringResource(R.string.menu_tags), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tag in state.tags) {
                FilterChip(selected = tag.id in form.tags, onClick = { menu.toggleFormTag(tag) }, label = { Text(tag.name) })
            }
        }
    }

    DishGroups(form, state, menu)

    if (state.allergens.isNotEmpty()) {
        AllergenPicker(state.allergens, form.allergens, enabled = true, onToggle = menu::toggleFormAllergen, onNone = menu::toggleFormAllergenNone)
    }

    if (dish == null) {
        Hint(stringResource(R.string.menu_photo_after_create))
    } else {
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
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.menu_available), modifier = Modifier.weight(1f))
        Switch(checked = form.available, onCheckedChange = { on -> menu.updateForm { it.copy(available = on) } })
    }
    if (problems.isNotEmpty() && form.name.isNotEmpty()) {
        Text(stringResource(R.string.menu_form_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val busy = (if (dish == null) "new" else "d${dish.id}") in state.busy
        if (dish != null) {
            TextButton(onClick = { menu.askArchiveDish(dish) }, enabled = !busy) {
                Text(stringResource(R.string.menu_archive), color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = menu::cancelEdit) { Text(stringResource(R.string.cancel)) }
        Button(
            onClick = { if (dish != null && form.categoryId != dish.categoryId) confirmMove = true else menu.saveDish() },
            enabled = !busy && !state.uploading && form.edit != null,
        ) {
            Text(stringResource(if (busy) R.string.please_wait else if (dish == null) R.string.menu_create else R.string.save))
        }
    }
    if (confirmMove) {
        AlertDialog(
            onDismissRequest = { confirmMove = false },
            text = { Text(stringResource(R.string.menu_move_category_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmMove = false
                        menu.saveDish()
                    }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { confirmMove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Which groups of choices the dish offers, in the order the customer meets
 * them. The groups themselves, and their choices, are made in the Optionen tab.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DishGroups(form: DishForm, state: MenuState, menu: MenuViewModel) {
    val live = state.groups.filterNot { it.archived }
    if (live.isEmpty()) return
    Text(stringResource(R.string.menu_dish_groups), style = MaterialTheme.typography.labelLarge)
    Hint(stringResource(R.string.menu_dish_groups_hint))
    for ((index, id) in form.groupIds.withIndex()) {
        val group = state.groups.find { it.id == id } ?: continue
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.name, fontWeight = FontWeight.Medium)
                Hint(stringResource(R.string.menu_group_choices, group.live.joinToString(", ") { it.name }.ifEmpty { "–" }))
            }
            TextButton(onClick = { menu.moveFormGroup(id, -1) }, enabled = index > 0) { Text(stringResource(R.string.menu_move_up)) }
            TextButton(onClick = { menu.moveFormGroup(id, 1) }, enabled = index < form.groupIds.lastIndex) { Text(stringResource(R.string.menu_move_down)) }
            TextButton(onClick = { menu.removeFormGroup(id) }) { Text(stringResource(R.string.menu_remove)) }
        }
    }
    val more = live.filter { it.id !in form.groupIds }
    if (more.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (group in more) {
                FilterChip(selected = false, onClick = { menu.addFormGroup(group.id) }, label = { Text("+ ${group.name}") })
            }
        }
    }
}

/** The five Zusatzstoffe the board prints, by number and word. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdditivePicker(value: List<Int>, onToggle: (Int) -> Unit) {
    Text(stringResource(R.string.menu_additives), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (number in Additives.ALL) {
            FilterChip(selected = number in value, onClick = { onToggle(number) }, label = { Text("$number ${additiveName(number)}") })
        }
    }
}

@Composable
private fun additiveName(number: Int): String =
    stringResource(
        when (number) {
            1 -> R.string.additive_1
            2 -> R.string.additive_2
            3 -> R.string.additive_3
            4 -> R.string.additive_4
            else -> R.string.additive_5
        }
    )

@Composable
internal fun NumberField(value: String, label: Int, keyboard: KeyboardType, modifier: Modifier, isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.widthIn(min = 120.dp),
    )
}

/** The square the menu draws, at the size it draws it, so what is shown here is what customers get. */
@Composable
internal fun PhotoPreview(url: String, menu: MenuViewModel) {
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

/** From here on two fields go side by side: a tablet, or a big phone held sideways. */
private const val WIDE_DP = 600
