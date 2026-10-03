package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Additives
import de.hamzabistro.printstation.core.Allergens
import de.hamzabistro.printstation.core.GroupForm
import de.hamzabistro.printstation.core.MenuOption
import de.hamzabistro.printstation.core.MenuTag
import de.hamzabistro.printstation.core.OptionForm
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.Selection

// -----------------------------------------------------------------------
// Options
// -----------------------------------------------------------------------

/**
 * The groups of choices: the sold-out switch on each choice, as before,
 * and behind a second tap the group itself — its name, one or several
 * choices, the dishes that offer it — and its choices with their prices.
 */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.options(state: MenuState, menu: MenuViewModel) {
    val live = state.groups.filterNot { it.archived }
    item(key = "options-intro") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuHint(stringResource(R.string.menu_options_intro))
            MenuHint(stringResource(R.string.menu_options_shared))
            val out = live.filter { it.offered }.sumOf { group -> group.live.count { !it.available } }
            Text(stringResource(R.string.menu_sold_out_count, out), fontWeight = FontWeight.SemiBold)
            MissingAllergens(
                Allergens.missing(live.flatMap { group -> group.live.map { it.allergens } }),
                state.onlyMissingAllergens,
                menu::toggleOnlyMissingAllergens,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { menu.startGroup(null) }, enabled = !state.showArchived) { Text(stringResource(R.string.menu_group_new)) }
                FilterChip(selected = state.arranging, onClick = menu::toggleArranging, label = { Text(stringResource(R.string.menu_arrange)) })
                FilterChip(selected = state.showArchived, onClick = menu::toggleArchived, label = { Text(stringResource(R.string.menu_archived_filter)) })
            }
        }
    }
    val newGroup = state.groupForm?.takeIf { it.id == null }
    if (newGroup != null) {
        item(key = "group-new") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupFormView(null, newGroup, state, menu)
                }
            }
        }
    }
    if (state.showArchived) {
        archivedOptions(state, menu)
        return
    }
    // The database allows some tags on a choice — the vegan leaf — and refuses others.
    val tags = state.tags.filter { it.onOptions }
    for (group in live) {
        val options = if (state.onlyMissingAllergens) group.live.filter { it.allergens == null } else group.live
        if (state.onlyMissingAllergens && options.isEmpty()) continue
        item(key = "group-${group.id}") { GroupHeader(group, state, menu) }
        val form = state.optionForm
        if (form != null && form.groupId == group.id && form.optionId == null) {
            item(key = "option-new-${group.id}") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.menu_option_new_title, group.name), style = MaterialTheme.typography.titleSmall)
                        OptionFormView(null, form, state, menu)
                    }
                }
            }
        }
        items(options, key = { "g${group.id}-o${it.id}" }) { option -> OptionCard(group, option, options, tags, state, menu) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupHeader(group: OptionGroup, state: MenuState, menu: MenuViewModel) {
    val editing = state.groupForm?.id == group.id
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Heading(group.name)
                MenuHint(stringResource(if (group.selection == Selection.SINGLE) R.string.menu_group_single else R.string.menu_group_multiple))
            }
            if (!state.arranging) {
                TextButton(onClick = { menu.startGroup(group) }, enabled = !editing) {
                    Text(stringResource(R.string.menu_edit))
                }
                TextButton(onClick = { menu.startOption(group.id, null) }) { Text(stringResource(R.string.menu_option_new)) }
            }
        }
        // Said first: it is the difference between chicken off one Döner and off all four.
        if (group.dishes.isEmpty()) {
            Text(stringResource(R.string.menu_group_on_no_dish), style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.SOON))
        } else {
            MenuHint(stringResource(R.string.menu_option_used_in, group.dishes.joinToString(", ")))
        }
        if (group.live.isEmpty()) {
            Text(stringResource(R.string.menu_group_no_choice), style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.SOON))
        }
        val form = state.groupForm
        if (editing && form != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupFormView(group, form, state, menu)
                }
            }
        }
    }
}

/**
 * A group's name and kind, and — for one already made — the dishes that
 * offer it, as ticks by category. A new group gets its first choice before
 * any dish: a single-choice group with nothing in it may not go on one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupFormView(group: OptionGroup?, form: GroupForm, state: MenuState, menu: MenuViewModel) {
    val busy = "g${group?.id ?: "new"}" in state.busy
    if (group == null) Text(stringResource(R.string.menu_group_new_title), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = form.name,
        onValueChange = { v -> menu.updateGroupForm { it.copy(name = v) } },
        label = { Text(stringResource(R.string.menu_name)) },
        isError = !form.valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = form.selection == Selection.SINGLE,
            onClick = { menu.updateGroupForm { it.copy(selection = Selection.SINGLE) } },
            label = { Text(stringResource(R.string.menu_group_single)) },
        )
        FilterChip(
            selected = form.selection == Selection.MULTIPLE,
            onClick = { menu.updateGroupForm { it.copy(selection = Selection.MULTIPLE) } },
            label = { Text(stringResource(R.string.menu_group_multiple)) },
        )
    }
    if (group == null) {
        MenuHint(stringResource(R.string.menu_group_new_hint))
    } else {
        HorizontalDivider()
        Text(stringResource(R.string.menu_group_dishes), style = MaterialTheme.typography.labelLarge)
        MenuHint(stringResource(R.string.menu_options_shared))
        if (form.selection == Selection.SINGLE && group.live.isEmpty()) MenuHint(stringResource(R.string.menu_group_no_choice))
        for ((category, dishes) in state.dishes.groupBy { it.category }) {
            MenuHint(category)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (dish in dishes) {
                    FilterChip(selected = dish.id in form.itemIds, onClick = { menu.toggleGroupDish(dish.id) }, label = { Text(dish.name) })
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (group != null) {
            TextButton(onClick = { menu.askArchiveGroup(group) }, enabled = !busy) {
                Text(stringResource(R.string.menu_archive), color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = menu::cancelForms) { Text(stringResource(R.string.cancel)) }
        Button(onClick = menu::saveGroup, enabled = !busy && form.valid) {
            Text(stringResource(if (busy) R.string.please_wait else if (group == null) R.string.menu_create else R.string.save))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionCard(
    group: OptionGroup,
    option: MenuOption,
    shown: List<MenuOption>,
    tags: List<MenuTag>,
    state: MenuState,
    menu: MenuViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            val busy = "o${option.id}" in state.busy
            if (state.arranging) {
                Arrange(
                    name = option.name,
                    first = shown.firstOrNull()?.id == option.id,
                    last = shown.lastOrNull()?.id == option.id,
                    busy = "g${group.id}" in state.busy,
                    onMove = { by -> menu.moveOption(group, option, by) },
                )
                return@Column
            }
            val editingAllergens = state.allergenOption == option.id
            val form = state.optionForm?.takeIf { it.optionId == option.id }
            SwitchLine(
                name = option.name,
                on = option.available,
                onText = priced(option, R.string.menu_available),
                offText = priced(option, R.string.menu_sold_out),
                busy = busy,
                onToggle = { menu.toggleOption(option) },
                extra = {
                    TextButton(onClick = { if (form != null) menu.cancelForms() else menu.startOption(group.id, option) }) {
                        Text(stringResource(if (form != null) R.string.cancel else R.string.menu_edit))
                    }
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
            if (form != null) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionFormView(option, form, state, menu)
                }
            }
            if (editingAllergens) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    MenuHint(stringResource(R.string.menu_allergens_option_shared))
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

/** "+1,00 € · Auf der Karte", or just the state for a free choice. */
@Composable
private fun priced(option: MenuOption, state: Int): String =
    if (option.price > 0) "+${Format.euro(option.price)} · ${stringResource(state)}" else stringResource(state)

/** A choice's name, price and Zusatzstoffe, for a new one ([option] null) or one there is. */
@Composable
private fun OptionFormView(option: MenuOption?, form: OptionForm, state: MenuState, menu: MenuViewModel) {
    val busy = "o${option?.id ?: "new"}" in state.busy
    HorizontalDivider()
    OutlinedTextField(
        value = form.name,
        onValueChange = { v -> menu.updateOptionForm { it.copy(name = v) } },
        label = { Text(stringResource(R.string.menu_name)) },
        isError = form.name.isBlank(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    NumberField(form.price, R.string.menu_option_price, KeyboardType.Decimal, Modifier.fillMaxWidth(), form.edit == null && form.name.isNotBlank()) { v ->
        menu.updateOptionForm { it.copy(price = v) }
    }
    MenuHint(stringResource(R.string.menu_option_price_hint))
    AdditivePicker(form.additives) { number -> menu.updateOptionForm { it.copy(additives = Additives.toggle(it.additives, number)) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.menu_available), modifier = Modifier.weight(1f))
        Switch(checked = form.available, onCheckedChange = { on -> menu.updateOptionForm { it.copy(available = on) } })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (option != null) {
            TextButton(onClick = { menu.askArchiveOption(option) }, enabled = !busy) {
                Text(stringResource(R.string.menu_archive), color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = menu::cancelForms) { Text(stringResource(R.string.cancel)) }
        Button(onClick = menu::saveOption, enabled = !busy && form.edit != null) {
            Text(stringResource(if (busy) R.string.please_wait else if (option == null) R.string.menu_create else R.string.save))
        }
    }
}

/** What was taken off: whole groups, and single choices of groups still in use. */
private fun LazyListScope.archivedOptions(state: MenuState, menu: MenuViewModel) {
    val groups = state.groups.filter { it.archived }
    val choices = state.groups.filterNot { it.archived }.flatMap { group -> group.options.filter { it.archived }.map { group to it } }
    item(key = "archived-options-intro") {
        MenuHint(stringResource(if (groups.isEmpty() && choices.isEmpty() && !state.loading) R.string.menu_archived_none else R.string.menu_archived_options_intro))
    }
    items(groups, key = { "ag${it.id}" }) { group ->
        ArchivedRow(group.name, stringResource(R.string.menu_archived_group), "g${group.id}" in state.busy) { menu.restoreGroup(group) }
    }
    items(choices, key = { (_, option) -> "ao${option.id}" }) { (group, option) ->
        ArchivedRow(option.name, group.name, "o${option.id}" in state.busy) { menu.restoreOption(option) }
    }
}

@Composable
private fun ArchivedRow(name: String, detail: String, busy: Boolean, onRestore: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                MenuHint(detail)
            }
            Button(onClick = onRestore, enabled = !busy) { Text(stringResource(R.string.menu_restore)) }
        }
    }
}
