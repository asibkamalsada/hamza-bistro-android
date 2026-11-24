package de.hamzabistro.printstation.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AddressStatus
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.Fulfilment
import de.hamzabistro.printstation.core.PhoneOrderDraft
import de.hamzabistro.printstation.core.PhoneOrderQuote
import de.hamzabistro.printstation.core.RecentAddress
import de.hamzabistro.printstation.core.StreetDelivery
import de.hamzabistro.printstation.core.StreetSuggestion

// Who an order is for and where it goes: the same fields, the same streets to
// pick from and the same words, step by step on the phone and in the full form.

/** What the screens of a phone or walk-in order can be asked to do: [NewOrderViewModel] does it, a drawing in a test does not. */
interface OrderActions {
    fun loadPhoto(url: String)

    fun loadMenu()

    fun edit(change: (PhoneOrderDraft) -> PhoneOrderDraft)

    fun add(line: BasketLine)

    fun setQty(index: Int, qty: Int)

    fun setNote(index: Int, note: String)

    fun clear()

    fun goTo(step: CallStep)

    fun pickStreet(street: StreetSuggestion)

    fun pickAddress(address: RecentAddress)

    fun checkAgain()

    fun sendLink()

    fun save()

    fun saveWaiting()

    /** The minutes an order for now is pre-set to. */
    fun suggestedMinutes(state: NewOrderState): Int
}

/** The menu as [state] has it, for [DishesPane]. */
internal fun orderShop(state: NewOrderState, photos: Map<String, ImageBitmap?>, model: OrderActions): Shop =
    Shop(state.menu, state.menuError, state.bestsellers, photos, model::loadPhoto, model::loadMenu)

/** One field, big enough to read what is typed. */
@Composable
internal fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        textStyle = TextStyle(fontSize = 20.sp),
        singleLine = singleLine,
        keyboardOptions = keyboard,
        modifier = modifier,
    )
}

/** Name and number. */
@Composable
internal fun ContactFields(state: NewOrderState, model: OrderActions, nameField: Modifier = Modifier) {
    val draft = state.draft
    FormField(
        draft.name,
        { v -> model.edit { it.copy(name = v) } },
        stringResource(R.string.new_order_name),
        nameField.fillMaxWidth(),
        KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    )
    // Filled in from the call; typed only when the shop's phone did not say.
    FormField(
        draft.phone,
        { v -> model.edit { it.copy(phone = v) } },
        stringResource(R.string.new_order_phone),
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
    )
}

/** Lieferung, Abholung or Vor Ort: those of [offered]. */
@Composable
internal fun FulfilmentChoice(state: NewOrderState, model: OrderActions, offered: List<Fulfilment>) {
    Choice(offered.map { stringResource(fulfilmentLabel(it)) }, offered.indexOf(state.draft.fulfilment), Modifier.fillMaxWidth()) { index ->
        model.edit { it.copy(fulfilment = offered[index]) }
    }
}

private fun fulfilmentLabel(fulfilment: Fulfilment): Int =
    when (fulfilment) {
        Fulfilment.PICKUP -> R.string.fulfilment_pickup
        Fulfilment.DINE_IN -> R.string.fulfilment_dine_in
        Fulfilment.DELIVERY -> R.string.fulfilment_delivery
    }

/**
 * The address. What helps with it — earlier addresses, streets to pick, what
 * check-address says — is between its fields, where it is for, unless it
 * stands beside them ([help] false, [AddressHelp]).
 */
@Composable
internal fun AddressFields(state: NewOrderState, model: OrderActions, help: Boolean) {
    val draft = state.draft
    if (help) RecentAddresses(state, model)
    FormField(
        draft.street,
        { v -> model.edit { it.copy(street = v) } },
        stringResource(R.string.new_order_street),
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    )
    if (help) StreetChoices(state, model)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(
            draft.postalCode,
            { v -> model.edit { it.copy(postalCode = v.filter(Char::isDigit).take(5)) } },
            stringResource(R.string.new_order_postal_code),
            Modifier.weight(1f),
            KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        FormField(
            draft.city,
            { v -> model.edit { it.copy(city = v) } },
            stringResource(R.string.new_order_city),
            Modifier.weight(2f),
            KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
    }
    FormField(
        draft.addressNote,
        { v -> model.edit { it.copy(addressNote = v) } },
        stringResource(R.string.new_order_door_note),
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
    )
    if (help) AddressVerdict(state, model)
}

/** Beside the fields, where there is room: in sight while the street is typed, keyboard or not. */
@Composable
internal fun AddressHelp(state: NewOrderState, model: OrderActions) {
    AddressVerdict(state, model)
    StreetChoices(state, model)
    RecentAddresses(state, model)
}

/** For a caller who has ordered before, one tap is the whole address. */
@Composable
private fun RecentAddresses(state: NewOrderState, model: OrderActions) {
    if (state.recentChoices.isEmpty()) return
    Text(stringResource(R.string.new_order_recent_addresses), style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (recent in state.recentChoices) {
        ChoiceRow(onClick = { model.pickAddress(recent) }) {
            Text(recent.line, style = LINE_NAME)
            if (recent.addressNote.isNotBlank()) Text(recent.addressNote, style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Leipzig's streets like the one typed. A tap takes the name as the city
 * spells it, its postcode and the town, so nobody spells "Zschochersche"
 * and no caller is asked for a postcode.
 */
@Composable
private fun StreetChoices(state: NewOrderState, model: OrderActions) {
    val resources = LocalResources.current
    for (street in state.streetChoices) {
        ChoiceRow(
            onClick = { model.pickStreet(street) },
            end = {
                // From the list, so only roughly: check-address has the last word on the door.
                when (street.delivery) {
                    StreetDelivery.OUTSIDE ->
                        Text(stringResource(R.string.new_order_street_outside), style = LINE_SMALL, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    StreetDelivery.PARTLY -> Text(stringResource(R.string.new_order_street_partly), style = LINE_SMALL, color = toneColor(Tone.SOON))
                    StreetDelivery.INSIDE -> Unit
                }
            },
        ) {
            Text(street.fullStreet, style = LINE_NAME)
            // With a house the list has: its own postcode, and the ring it is in.
            Text(
                listOfNotNull(street.postcodes.joinToString(", "), street.entry.district, street.ringZone?.let { ringName(resources, it) }).joinToString(" · "),
                style = LINE_SMALL,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said, and no more than that: the number may be misheard, or the house new.
            if (street.houseUnlisted) {
                Text(stringResource(R.string.new_order_house_unlisted, street.houseNumber.orEmpty()), style = LINE_SMALL, color = toneColor(Tone.SOON))
            }
        }
    }
    if (state.streetUnlisted) {
        Text(stringResource(R.string.new_order_street_unlisted), style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Something to pick with a tap. */
@Composable
private fun ChoiceRow(onClick: () -> Unit, end: @Composable () -> Unit = {}, words: @Composable ColumnScope.() -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), content = words)
            end()
        }
    }
}

/** What check-address says about the address in the fields. */
@Composable
internal fun AddressVerdict(state: NewOrderState, model: OrderActions) {
    val resources = LocalResources.current
    val address = state.address
    when {
        state.checking -> StatusLine(stringResource(R.string.new_order_checking_address))
        state.addressError != null -> {
            StatusLine(stringResource(R.string.new_order_address_failed, state.addressError), bad = true)
            BigButton(stringResource(R.string.new_order_check_again), onClick = model::checkAgain, outlined = true)
        }
        // Not the answer for the address typed before: this one is asked about in a moment.
        address == null || !state.addressChecked -> Unit
        address.outside -> StatusLine(stringResource(R.string.call_order_outside), bad = true)
        address.status == AddressStatus.VERIFIED ->
            StatusLine("✓ " + stringResource(R.string.new_order_address_ok, ringName(resources, address.zone)), good = true)
        address.status == AddressStatus.UNVERIFIED -> StatusLine(stringResource(R.string.new_order_address_unverified), soon = true)
        else -> {
            StatusLine(stringResource(R.string.new_order_address_unchecked), soon = true)
            BigButton(stringResource(R.string.new_order_check_again), onClick = model::checkAgain, outlined = true)
        }
    }
    address?.suggestedPostalCode?.takeIf { state.addressChecked }?.let { code ->
        StatusLine(stringResource(R.string.new_order_address_suggest, code))
        BigButton(stringResource(R.string.new_order_address_use, code), onClick = { model.edit { it.copy(postalCode = code) } }, outlined = true)
    }
}

/** The ring's name as the report says it, or the ring's own word. */
internal fun ringName(resources: Resources, zone: String?): String =
    when (zone) {
        "inner" -> resources.getString(R.string.report_ring_inner)
        "near" -> resources.getString(R.string.report_ring_near)
        "far" -> resources.getString(R.string.report_ring_far)
        "edge" -> resources.getString(R.string.report_ring_edge)
        else -> zone.orEmpty()
    }

/** The price as the database works it out; what it is still waiting for, or why there is none. */
@Composable
internal fun PriceLines(state: NewOrderState) {
    val quote = state.quote
    when {
        state.quoting || state.checking ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                StatusLine(stringResource(R.string.new_order_pricing))
            }
        quote != null && state.priced -> Breakdown(quote)
        state.error == null -> Text(stringResource(R.string.new_order_price_pending), style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Breakdown(quote: PhoneOrderQuote) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PriceLine(stringResource(R.string.new_order_subtotal), quote.subtotal)
        if (quote.dealDiscount > 0) PriceLine(stringResource(R.string.new_order_deal), -quote.dealDiscount, note = true)
        if (quote.pickupDiscount > 0) PriceLine(stringResource(R.string.new_order_pickup_discount), -quote.pickupDiscount)
        if (quote.deliveryFee > 0) PriceLine(stringResource(R.string.new_order_delivery_fee), quote.deliveryFee)
        if (quote.smallOrderFee > 0) PriceLine(stringResource(R.string.new_order_small_fee), quote.smallOrderFee)
        if (quote.waived > 0) PriceLine(stringResource(R.string.new_order_waived), -quote.waived, note = true)
        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        PriceLine(stringResource(R.string.new_order_total), quote.total, bold = true)
        if (quote.deposits > 0) {
            Text(stringResource(R.string.new_order_deposits, Format.euro(quote.deposits)), style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (quote.overCapacity) StatusLine(stringResource(R.string.new_order_over_capacity), soon = true)
    }
}

/** [note]: already inside the line above (the deal is in the dishes' prices; the waiver is not charged), shown for information. */
@Composable
internal fun PriceLine(label: String, amount: Double, bold: Boolean = false, note: Boolean = false) {
    val style = if (bold) TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold) else TextStyle(fontSize = 18.sp, lineHeight = 24.sp)
    val color = if (note) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row {
        Text(label, style = style, color = color, modifier = Modifier.weight(1f))
        Text(Format.euro(amount), style = style, color = color, maxLines = 1)
    }
}
