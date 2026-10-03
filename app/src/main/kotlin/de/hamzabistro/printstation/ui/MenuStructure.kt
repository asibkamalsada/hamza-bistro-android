package de.hamzabistro.printstation.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.CategoryForm
import de.hamzabistro.printstation.core.DealForm
import de.hamzabistro.printstation.core.MenuCategory
import de.hamzabistro.printstation.core.MenuDeal
import de.hamzabistro.printstation.core.MenuEdits

// -----------------------------------------------------------------------
// Categories
// -----------------------------------------------------------------------

/** The menu's sections: add one, rename it, give it a photo, put them in order. */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.categories(state: MenuState, menu: MenuViewModel) {
    item(key = "categories-intro") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuHint(stringResource(R.string.menu_categories_intro))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { menu.startCategory(null) }, enabled = !state.arranging) { Text(stringResource(R.string.menu_category_new)) }
                FilterChip(selected = state.arranging, onClick = menu::toggleArranging, label = { Text(stringResource(R.string.menu_arrange)) })
            }
        }
    }
    val newForm = state.categoryForm?.takeIf { it.id == null }
    if (newForm != null) {
        item(key = "category-new") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryFormView(null, newForm, state, menu)
                }
            }
        }
    }
    items(state.categories, key = { "cat${it.id}" }) { category ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.arranging) {
                    Arrange(
                        name = category.name,
                        first = state.categories.firstOrNull()?.id == category.id,
                        last = state.categories.lastOrNull()?.id == category.id,
                        busy = "categories" in state.busy,
                        onMove = { by -> menu.moveCategory(category, by) },
                    )
                    return@Column
                }
                val editing = state.categoryForm?.id == category.id
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhotoPreview(category.imageUrl, menu)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(category.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        val count = state.dishes.count { it.categoryId == category.id }
                        MenuHint(if (count == 0) stringResource(R.string.menu_category_hidden) else pluralStringResource(R.plurals.menu_category_dishes, count, count))
                    }
                    TextButton(onClick = { if (editing) menu.cancelForms() else menu.startCategory(category) }) {
                        Text(stringResource(if (editing) R.string.cancel else R.string.menu_edit))
                    }
                }
                val form = state.categoryForm
                if (editing && form != null) CategoryFormView(category, form, state, menu)
            }
        }
    }
}

@Composable
private fun CategoryFormView(category: MenuCategory?, form: CategoryForm, state: MenuState, menu: MenuViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(menu::pickCategoryPhoto) }
    val busy = "c${category?.id ?: "new"}" in state.busy
    HorizontalDivider()
    if (category == null) Text(stringResource(R.string.menu_category_new_title), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = form.name,
        onValueChange = { v -> menu.updateCategoryForm { it.copy(name = v) } },
        label = { Text(stringResource(R.string.menu_name)) },
        isError = !form.valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (category == null) {
        MenuHint(stringResource(R.string.menu_category_new_hint))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoPreview(form.imageUrl, menu)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.uploading) {
                        Text(stringResource(R.string.menu_photo_pick))
                    }
                    if (form.imageUrl.isNotBlank()) {
                        TextButton(onClick = { menu.updateCategoryForm { it.copy(imageUrl = "") } }) { Text(stringResource(R.string.menu_photo_remove)) }
                    }
                }
                MenuHint(
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
            onValueChange = { v -> menu.updateCategoryForm { it.copy(imageUrl = v) } },
            label = { Text(stringResource(R.string.menu_photo_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = menu::cancelForms) { Text(stringResource(R.string.cancel)) }
        Button(onClick = menu::saveCategory, enabled = !busy && !state.uploading && form.valid) {
            Text(stringResource(if (busy) R.string.please_wait else if (category == null) R.string.menu_create else R.string.save))
        }
    }
}

// -----------------------------------------------------------------------
// Angebote
// -----------------------------------------------------------------------

/** The week as seven rows, Monday first: each day a category or one dish, so much cheaper, or nothing. */
internal fun LazyListScope.deals(state: MenuState, menu: MenuViewModel) {
    item(key = "deals-intro") { MenuHint(stringResource(R.string.menu_deals_intro)) }
    items(MenuEdits.WEEK, key = { "day$it" }) { day ->
        val deal = state.deals.find { it.day == day }
        val form = state.dealForm?.takeIf { it.day == day }
        val busy = "w$day" in state.busy
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dayName(LocalContext.current, day), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (deal == null) MenuHint(stringResource(R.string.menu_deal_none)) else Text(dealLine(deal, state))
                    }
                    TextButton(onClick = { if (form != null) menu.cancelForms() else menu.startDeal(day) }) {
                        Text(stringResource(if (form != null) R.string.cancel else R.string.menu_edit))
                    }
                }
                if (form != null) DealFormView(deal, form, busy, state, menu)
            }
        }
    }
}

/** "Döner · −1,00 €", or "Döner Teller (Döner) · −1,00 €" for one dish. */
private fun dealLine(deal: MenuDeal, state: MenuState): String {
    val category = state.categories.find { it.id == deal.categoryId }?.name ?: "#${deal.categoryId}"
    val what = deal.itemId?.let { id -> state.dishes.find { it.id == id }?.name?.let { "$it ($category)" } ?: "#$id ($category)" } ?: category
    return "$what · −${Format.euro(deal.discount)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DealFormView(deal: MenuDeal?, form: DealForm, busy: Boolean, state: MenuState, menu: MenuViewModel) {
    HorizontalDivider()
    Text(stringResource(R.string.menu_category), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (category in state.categories) {
            FilterChip(
                selected = form.categoryId == category.id,
                onClick = { menu.updateDealForm { it.copy(categoryId = category.id, itemId = null) } },
                label = { Text(category.name) },
            )
        }
    }
    val categoryId = form.categoryId
    if (categoryId != null) {
        Text(stringResource(R.string.menu_deal_what), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = form.itemId == null, onClick = { menu.updateDealForm { it.copy(itemId = null) } }, label = { Text(stringResource(R.string.menu_deal_whole_category)) })
            for (dish in state.dishes.filter { it.categoryId == categoryId }) {
                FilterChip(selected = form.itemId == dish.id, onClick = { menu.updateDealForm { it.copy(itemId = dish.id) } }, label = { Text(dish.name) })
            }
        }
    }
    NumberField(form.discount, R.string.menu_deal_discount, KeyboardType.Decimal, Modifier.fillMaxWidth(), form.discount.isNotBlank() && !form.valid) { v ->
        menu.updateDealForm { it.copy(discount = v) }
    }
    MenuHint(stringResource(R.string.menu_deal_discount_hint))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (deal != null) {
            TextButton(onClick = { menu.clearDeal(form.day) }, enabled = !busy) {
                Text(stringResource(R.string.menu_deal_clear), color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = menu::cancelForms) { Text(stringResource(R.string.cancel)) }
        Button(onClick = menu::saveDeal, enabled = !busy && form.valid) {
            Text(stringResource(if (busy) R.string.please_wait else R.string.save))
        }
    }
}

// -----------------------------------------------------------------------
// Asked first
// -----------------------------------------------------------------------

/** "Archivieren?" before anything leaves the menu, and HB451's offer to bring a dish back. */
@Composable
internal fun MenuDialogs(state: MenuState, menu: MenuViewModel) {
    when (val confirm = state.confirm) {
        is Confirm.ArchiveDish ->
            ArchiveDialog(stringResource(R.string.menu_archive_dish_confirm, confirm.dish.name), menu::dismissConfirm) { menu.archiveDish(confirm.dish) }
        is Confirm.ArchiveGroup ->
            ArchiveDialog(
                stringResource(R.string.menu_archive_group_confirm, confirm.group.name, confirm.group.dishes.size),
                menu::dismissConfirm,
            ) {
                menu.archiveGroup(confirm.group)
            }
        is Confirm.ArchiveOption ->
            ArchiveDialog(stringResource(R.string.menu_archive_option_confirm, confirm.option.name), menu::dismissConfirm) { menu.archiveOption(confirm.option) }
        null -> {}
    }
    state.restoreOffer?.let { dish ->
        AlertDialog(
            onDismissRequest = menu::dismissRestoreOffer,
            title = { Text(stringResource(R.string.menu_error_name_taken)) },
            text = { Text(stringResource(R.string.menu_restore_offer, dish.name)) },
            confirmButton = { TextButton(onClick = menu::restoreOffered) { Text(stringResource(R.string.menu_restore)) } },
            dismissButton = { TextButton(onClick = menu::dismissRestoreOffer) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ArchiveDialog(text: String, onDismiss: () -> Unit, onArchive: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_archive_title)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onArchive) { Text(stringResource(R.string.menu_archive), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
