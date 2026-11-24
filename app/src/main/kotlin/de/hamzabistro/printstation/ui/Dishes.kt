package de.hamzabistro.printstation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.Bestsellers
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.MenuOption
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OptionRules
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.PhoneOrders
import de.hamzabistro.printstation.core.Selection

// The dishes, their choices and the basket: what every order is put together
// with, rung up at the counter or taken on the phone. One set of them, so
// whoever knows one screen knows the others.

/** The button that goes on with the basket. */
const val BASKET_GO_TAG = "basket-go"

/** One dish to tap. */
const val DISH_TILE_TAG = "dish-tile"

/** The line below the bestsellers. */
const val BESTSELLERS_END_TAG = "bestsellers-end"

/** One line of the basket. */
const val BASKET_LINE_TAG = "basket-line"

/** From this width on there is room beside the thing somebody works on: the basket, the sale's lines, the keys. */
internal val WIDE_FROM = 840.dp

/** Where the basket is. */
internal enum class BasketPlace {
    /** Beside the dishes: the tablet, lying. */
    BESIDE,

    /** Below them: the tablet, standing. */
    BELOW,

    /** A tap away: a phone. */
    AWAY;

    companion object {
        fun of(width: Dp, height: Dp): BasketPlace =
            when {
                width >= WIDE_FROM -> BESIDE
                width >= 600.dp && height >= 700.dp -> BELOW
                width >= 720.dp -> BESIDE
                else -> AWAY
            }
    }
}

/** What a basket line, a dish and an amount are called by: read at arm's length. */
internal val LINE_NAME = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold, hyphens = Hyphens.Auto)
internal val LINE_SMALL = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = 0.sp)

/** The menu to tap from: null until it is read, [error] when it could not be. */
internal class Shop(
    val menu: OrderMenu?,
    val error: String?,
    /** The dishes to show first, ids. */
    val bestsellers: List<Long>,
    val photos: Map<String, ImageBitmap?>,
    val onPhoto: (String) -> Unit,
    val onRetry: () -> Unit,
)

/** What is in the basket, and what can be done to it. */
internal class Basket(
    val lines: List<BasketLine>,
    /** What stands on the way to the basket on a phone. */
    val total: Double,
    val toBasket: String,
    val onAdd: (BasketLine) -> Unit,
    val onQty: (Int, Int) -> Unit,
    /** What the deal of the day takes off, once the database has said so. */
    val deal: Double? = null,
    /** Takes a dish's note for the kitchen; null where there are none. */
    val onNote: ((Int, String) -> Unit)? = null,
)

/**
 * The dishes with the basket where there is room for it: beside them, below
 * them, or a tap away. [over] is above the basket's lines and [go] below
 * them, always in reach. [above] stands over the dishes; [problem] lies on
 * them, so no tile moves away under a finger.
 */
@Composable
internal fun DishesPane(
    shop: Shop,
    basket: Basket,
    over: @Composable (Modifier) -> Unit,
    go: @Composable (Modifier) -> Unit,
    above: @Composable () -> Unit = {},
    problem: (@Composable (Modifier) -> Unit)? = null,
) {
    var picking by remember { mutableStateOf<MenuDish?>(null) }
    var basketUp by rememberSaveable { mutableStateOf(false) }
    val empty = basket.lines.isEmpty()
    // The next customer starts with the dishes.
    LaunchedEffect(empty) { if (empty) basketUp = false }
    val menu = shop.menu
    val tap: (MenuDish, List<OptionGroup>) -> Unit = { dish, groups ->
        // Nothing to choose: in the basket with one tap.
        if (groups.isEmpty()) basket.onAdd(BasketLine(dish, 1)) else picking = dish
    }
    val dishes: @Composable (Boolean, Modifier) -> Unit = { rail, modifier ->
        Box(modifier) {
            Column(Modifier.fillMaxSize()) {
                above()
                DishesOrWhyNot(shop, basket.lines, rail, tap, Modifier.weight(1f).fillMaxWidth())
            }
            problem?.invoke(Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (BasketPlace.of(maxWidth, maxHeight)) {
            BasketPlace.BESIDE -> {
                val width = if (maxWidth >= 1000.dp) 432.dp else maxWidth * 0.42f
                Row(Modifier.fillMaxSize()) {
                    dishes(true, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    BasketColumn(basket, over, go, Modifier.width(width).fillMaxHeight())
                }
            }
            BasketPlace.BELOW -> {
                val height = maxHeight * 0.45f
                Column(Modifier.fillMaxSize()) {
                    dishes(true, Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider()
                    BasketBelow(basket, over, go, Modifier.height(height).fillMaxWidth())
                }
            }
            BasketPlace.AWAY -> {
                val up = basketUp && !empty
                BackHandler(enabled = up) { basketUp = false }
                Column(Modifier.fillMaxSize()) {
                    if (up) {
                        TextButton(onClick = { basketUp = false }, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.till_dishes), style = MaterialTheme.typography.titleMedium)
                        }
                        problem?.invoke(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        BasketColumn(basket, over, go, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        dishes(false, Modifier.weight(1f).fillMaxWidth())
                        HorizontalDivider()
                        ToBasket(basket) { basketUp = true }
                    }
                }
            }
        }
        val dish = picking
        if (dish != null && menu != null) {
            val groups = remember(dish, menu) { OptionRules.groups(dish, menu.groups) }
            DishOptions(dish, groups, notes = basket.onNote != null, onDismiss = { picking = null }) {
                basket.onAdd(it)
                picking = null
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The menu
// ---------------------------------------------------------------------------

/** The dishes to tap; until they are there, that they are coming, or why not. */
@Composable
internal fun DishesOrWhyNot(shop: Shop, lines: List<BasketLine>, rail: Boolean, onTap: (MenuDish, List<OptionGroup>) -> Unit, modifier: Modifier) {
    Box(modifier) {
        when {
            shop.menu != null -> DishMenu(shop.menu, shop.bestsellers, lines, shop.photos, shop.onPhoto, rail, onTap)
            shop.error != null ->
                Column(
                    Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.new_order_menu_failed, shop.error),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    BigButton(stringResource(R.string.retry), onClick = shop.onRetry, outlined = true)
                }
            else ->
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.new_order_menu_loading), style = MaterialTheme.typography.titleLarge)
                }
        }
    }
}

/**
 * A category at a time: the categories down the side, all in sight, or along
 * the top where there is no side. All of them together start with what sells
 * most today; within its category a dish stays where the card has it.
 */
@Composable
private fun DishMenu(
    menu: OrderMenu,
    bestsellers: List<Long>,
    lines: List<BasketLine>,
    photos: Map<String, ImageBitmap?>,
    onPhoto: (String) -> Unit,
    rail: Boolean,
    onTap: (MenuDish, List<OptionGroup>) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val dishes = remember(menu) { menu.dishes.filterNot { it.archived } }
    val categories = remember(dishes) { listOf<String?>(null) + dishes.map { it.category }.distinct() }
    val shown =
        remember(dishes, bestsellers, category) {
            if (category == null) Bestsellers.onTop(dishes, bestsellers) else Bestsellers.Arranged(dishes.filter { it.category == category }, 0)
        }
    // From the top whenever something else is shown: the grid would follow the dish it showed first to its new place.
    val place = remember(category, bestsellers) { LazyGridState() }
    val grid: @Composable (Modifier) -> Unit = { modifier ->
        DishGrid(shown, place, menu, lines, photos, onPhoto, rail, onTap, modifier)
    }
    if (rail) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.width(140.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (name in categories) CategoryButton(name, category == name, Modifier.fillMaxWidth()) { category = name }
            }
            grid(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column {
            // To the screen's edge: what is cut off there is scrolled to.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (name in categories) CategoryButton(name, category == name, Modifier) { category = name }
            }
            grid(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun CategoryButton(name: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sight = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) sight.bringIntoView() }
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.bringIntoViewRequester(sight),
    ) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                name ?: stringResource(R.string.call_order_all_dishes),
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(hyphens = Hyphens.Auto),
            )
        }
    }
}

@Composable
private fun DishGrid(
    shown: Bestsellers.Arranged,
    place: LazyGridState,
    menu: OrderMenu,
    lines: List<BasketLine>,
    photos: Map<String, ImageBitmap?>,
    onPhoto: (String) -> Unit,
    rail: Boolean,
    onTap: (MenuDish, List<OptionGroup>) -> Unit,
    modifier: Modifier,
) {
    // One height for all, photo or not, with room for the name whatever the font size: the tiles line up. A long name
    // takes its lines from the photo.
    val height = with(LocalDensity.current) { 80.dp + (LINE_NAME.lineHeight * 2).toDp() + PRICE_LINE.toDp() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        state = place,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = if (rail) PaddingValues(vertical = 8.dp) else PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp),
        modifier = modifier,
    ) {
        val tile: @Composable (MenuDish) -> Unit = { dish ->
            val groups = remember(dish, menu.groups) { OptionRules.groups(dish, menu.groups) }
            val count = lines.filter { it.dish.id == dish.id }.sumOf { it.qty }
            LaunchedEffect(dish.imageUrl) { onPhoto(dish.imageUrl) }
            DishTile(dish, photos[dish.imageUrl], OptionRules.orderable(dish, groups), count, Modifier.height(height)) { onTap(dish, groups) }
        }
        items(shown.dishes.take(shown.top), key = { it.id }) { tile(it) }
        // Where the bestsellers end, and the card in its own order begins.
        if (shown.top in 1 until shown.dishes.size) {
            item(key = "rest", span = { GridItemSpan(maxLineSpan) }) {
                HorizontalDivider(thickness = 2.dp, modifier = Modifier.padding(vertical = 4.dp).testTag(BESTSELLERS_END_TAG))
            }
        }
        items(shown.dishes.drop(shown.top), key = { it.id }) { tile(it) }
    }
}

private val PRICE_LINE = 24.sp

@Composable
private fun DishTile(dish: MenuDish, photo: ImageBitmap?, orderable: Boolean, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = orderable,
        colors =
            CardDefaults.cardColors(
                containerColor = if (count > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        modifier = modifier.fillMaxWidth().testTag(DISH_TILE_TAG),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (photo != null) {
                Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().weight(1f).alpha(if (orderable) 1f else 0.4f))
            }
            Column(
                if (photo != null) Modifier.padding(horizontal = 6.dp, vertical = 6.dp) else Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(dish.name, style = LINE_NAME, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (orderable) Format.euro(dish.price) else stringResource(R.string.new_order_sold_out),
                        fontSize = 17.sp,
                        lineHeight = PRICE_LINE,
                        color = if (orderable) Color.Unspecified else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (count > 0) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                            Text("$count×", fontSize = 17.sp, lineHeight = PRICE_LINE, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A dish's choices, over the whole screen: two columns of groups where there
 * is room, so they all show at once, and how many beside the button that adds
 * them. What can be chosen is [OptionRules]'; with [notes], a note for the
 * kitchen can be typed below.
 */
@Composable
internal fun DishOptions(dish: MenuDish, groups: List<OptionGroup>, notes: Boolean, onDismiss: () -> Unit, onAdd: (BasketLine) -> Unit) {
    var chosen by remember(dish.id) { mutableStateOf(OptionRules.preset(groups)) }
    var qty by remember(dish.id) { mutableIntStateOf(1) }
    var note by remember(dish.id) { mutableStateOf("") }
    val line = BasketLine(dish, qty, OptionRules.chosen(groups, chosen), note)
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_FROM
            val roomy = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(dish.name, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                    val columns = if (wide && groups.size > 1) groups.chunked((groups.size + 1) / 2) else listOf(groups)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        for (column in columns) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (group in column) {
                                    OptionChoices(group, chosen[group.id].orEmpty()) { chosen = OptionRules.toggle(group, chosen, it) }
                                }
                            }
                        }
                    }
                    if (notes) NoteField(note) { note = it }
                }
                HorizontalDivider()
                val stepper: @Composable () -> Unit = { QtyStepper(qty, dish.name) { qty = it.coerceIn(1, PhoneOrders.MAX_QTY) } }
                val add: @Composable (Modifier) -> Unit = { modifier ->
                    Button(
                        onClick = { onAdd(line) },
                        enabled = OptionRules.invalid(groups, chosen).isEmpty(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        modifier = modifier.heightIn(min = 64.dp),
                    ) {
                        ButtonWords(stringResource(R.string.call_order_add), Format.euro(line.unitPrice * qty), big = false)
                    }
                }
                BottomBar {
                    BigButton(stringResource(R.string.cancel_back), onClick = onDismiss, outlined = true)
                    if (!roomy) Spacer(Modifier.weight(1f))
                    stepper()
                    if (roomy) add(Modifier.weight(1f))
                }
                // A phone has no room for all of it in one row.
                if (!roomy) add(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
        }
    }
}

@Composable
private fun NoteField(note: String, onNote: (String) -> Unit) {
    OutlinedTextField(
        value = note,
        onValueChange = { onNote(it.take(PhoneOrders.NOTE_MAX)) },
        label = { Text(stringResource(R.string.new_order_line_note)) },
        textStyle = TextStyle(fontSize = 18.sp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionChoices(group: OptionGroup, chosen: List<Long>, onToggle: (MenuOption) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val hint = stringResource(if (group.selection == Selection.SINGLE) R.string.new_order_single_hint else R.string.new_order_multiple_hint)
        Text("${group.name} · $hint", fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in group.options) {
                val on = option.id in chosen
                // Chosen is a colour, not a tick: what is beside it must not move away under the next tap.
                val label = buildString {
                    append(option.name)
                    if (option.price > 0) append(" +").append(Format.euro(option.price))
                }
                Surface(
                    selected = on,
                    onClick = { onToggle(option) },
                    enabled = option.available,
                    shape = MaterialTheme.shapes.medium,
                    color =
                        when {
                            on -> MaterialTheme.colorScheme.primary
                            option.available -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    contentColor =
                        when {
                            on -> MaterialTheme.colorScheme.onPrimary
                            option.available -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                ) {
                    Column(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                        Text(label, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                        if (!option.available) Text(stringResource(R.string.new_order_sold_out), style = LINE_SMALL, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The basket
// ---------------------------------------------------------------------------

/** A column: what is over the lines, the lines, and the button that goes on below, always in reach. */
@Composable
private fun BasketColumn(basket: Basket, over: @Composable (Modifier) -> Unit, go: @Composable (Modifier) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    Column(modifier) {
        over(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp))
        BasketLines(basket, list, Modifier.weight(1f).fillMaxWidth())
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UnderBasket(basket, list)
            go(Modifier.fillMaxWidth())
        }
    }
}

/** Across the whole width: the lines above, everything else in one row. */
@Composable
private fun BasketBelow(basket: Basket, over: @Composable (Modifier) -> Unit, go: @Composable (Modifier) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    Column(modifier) {
        BasketLines(basket, list, Modifier.weight(1f).fillMaxWidth())
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            over(Modifier.weight(1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UnderBasket(basket, list)
                go(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Everything out of the basket again: asked first, for the bin is beside other buttons. */
@Composable
internal fun EmptyBasketButton(enabled: Boolean, onClear: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    val words = stringResource(R.string.till_clear)
    IconButton(onClick = { asking = true }, enabled = enabled, modifier = Modifier.size(52.dp)) {
        Icon(painterResource(R.drawable.ic_delete), contentDescription = words)
    }
    if (asking && enabled) {
        AskFirst(stringResource(R.string.till_clear_title), null, words, onDismiss = { asking = false }) {
            asking = false
            onClear()
        }
    }
}

/** What goes on with the basket. The amount and the button are one: there is nothing to look for. */
@Composable
internal fun GoButton(text: String, amount: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = totalColors(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 72.dp).testTag(BASKET_GO_TAG),
    ) {
        ButtonWords(text, amount)
    }
}

/** One of a few: side by side, all in sight. */
@Composable
internal fun Choice(words: List<String>, selected: Int, modifier: Modifier, onChoose: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier) {
        words.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selected,
                onClick = { onChoose(index) },
                shape = SegmentedButtonDefaults.itemShape(index, words.size),
                icon = {},
                label = { Text(label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.heightIn(min = 52.dp),
            )
        }
    }
}

@Composable
private fun BasketLines(basket: Basket, list: LazyListState, modifier: Modifier) {
    val lines = basket.lines
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.till_empty), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    FollowTheTap(lines, list)
    LazyColumn(modifier, state = list) {
        itemsIndexed(lines, key = { _, line -> "${line.dish.id}:${line.options.joinToString(",") { it.id.toString() }}:${line.note.trim()}" }) { index, line ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            BasketRow(line, { basket.onQty(index, it) }, basket.onNote?.let { { note: String -> it(index, note) } })
        }
    }
}

/** The line a tap has just added to is brought into sight. */
@Composable
private fun FollowTheTap(lines: List<BasketLine>, list: LazyListState) {
    var before by remember { mutableStateOf(lines) }
    LaunchedEffect(lines) {
        val more = lines.sumOf { it.qty } > before.sumOf { it.qty }
        val changed = lines.indices.firstOrNull { lines[it] != before.getOrNull(it) }
        before = lines
        if (!more || changed == null) return@LaunchedEffect
        val view = list.layoutInfo
        val inSight = view.visibleItemsInfo.any { it.index == changed && it.offset >= view.viewportStartOffset && it.offset + it.size <= view.viewportEndOffset }
        if (!inSight) list.animateScrollToItem(changed)
    }
}

/** One less, how many, one more. */
@Composable
internal fun QtyStepper(qty: Int, dish: String, onQty: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        QtyButton("−", stringResource(R.string.till_one_less, dish)) { onQty(qty - 1) }
        Text(
            qty.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        QtyButton("+", stringResource(R.string.till_one_more, dish)) { onQty(qty + 1) }
    }
}

/** How many, of what, for how much — one row, its buttons big; a tap on the words is its note, where there are notes. */
@Composable
internal fun BasketRow(line: BasketLine, onQty: (Int) -> Unit, onNote: ((String) -> Unit)? = null, modifier: Modifier = Modifier.padding(horizontal = 12.dp)) {
    var noting by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 3.dp).testTag(BASKET_LINE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QtyStepper(line.qty, line.dish.name, onQty)
        Spacer(Modifier.width(12.dp))
        val words = Modifier.weight(1f)
        Column(if (onNote == null) words else words.heightIn(min = 48.dp).clickable { noting = true }, verticalArrangement = Arrangement.Center) {
            Row {
                Text(line.dish.name, style = LINE_NAME, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(Format.euro(line.unitPrice * line.qty), style = LINE_NAME, maxLines = 1)
            }
            if (line.options.isNotEmpty()) {
                Text(line.options.joinToString(", ") { it.name }, style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val note = line.note.trim()
            if (note.isNotEmpty()) Text("„$note“", style = LINE_SMALL, color = MaterialTheme.colorScheme.error)
            else if (onNote != null) Text(stringResource(R.string.line_note_add), style = LINE_SMALL, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (noting && onNote != null) {
        var note by remember { mutableStateOf(line.note) }
        AlertDialog(
            onDismissRequest = { noting = false },
            title = { Text(line.dish.name) },
            text = { NoteField(note) { note = it } },
            confirmButton = {
                Button(
                    onClick = {
                        noting = false
                        onNote(note)
                    },
                    modifier = Modifier.heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.call_order_add), style = MaterialTheme.typography.titleMedium)
                }
            },
            dismissButton = {
                TextButton(onClick = { noting = false }, modifier = Modifier.heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.cancel_back), style = MaterialTheme.typography.titleMedium)
                }
            },
        )
    }
}

@Composable
private fun QtyButton(text: String, words: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        modifier = Modifier.size(48.dp).semantics { contentDescription = words },
    ) {
        Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** How many lines are further down, so none is forgotten; and the deal of the day, once the database has taken it off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnderBasket(basket: Basket, list: LazyListState) {
    if (basket.lines.isEmpty()) return
    // Cut off by no more than its margin, a line is all there.
    val margin = with(LocalDensity.current) { 6.dp.roundToPx() }
    val hidden by remember(list, margin) {
        derivedStateOf {
            val view = list.layoutInfo
            val last = view.visibleItemsInfo.lastOrNull { it.offset + it.size <= view.viewportEndOffset + margin }
            if (last == null) 0 else view.totalItemsCount - 1 - last.index
        }
    }
    val deal = basket.deal
    if (hidden <= 0 && deal == null) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            if (hidden > 0) stringResource(R.string.till_more_below, hidden) else "",
            style = LINE_SMALL,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (deal != null) Text(stringResource(R.string.new_order_deal) + "  " + Format.euro(-deal), style = LINE_SMALL)
    }
}

/** The total stays readable while its button waits. */
@Composable
private fun totalColors() =
    ButtonDefaults.buttonColors(
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/** A button's words at its left, its amount, bigger, at its right. */
@Composable
private fun RowScope.ButtonWords(text: String, amount: String, big: Boolean = true) {
    Text(text, fontSize = if (big) 22.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(8.dp))
    Text(amount, fontSize = if (big) 28.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/** On a phone, under the dishes: what is in the basket, and on to it. */
@Composable
private fun ToBasket(basket: Basket, onClick: () -> Unit) {
    val count = basket.lines.sumOf { it.qty }
    Button(
        onClick = onClick,
        enabled = count > 0,
        colors = totalColors(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 64.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.till_basket, count, count), fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1)
            Text(Format.euro(basket.total), fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(basket.toBasket, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
