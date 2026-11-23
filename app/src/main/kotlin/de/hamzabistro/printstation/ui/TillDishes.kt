package de.hamzabistro.printstation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.CounterSale
import de.hamzabistro.printstation.core.CounterSales
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.MenuOption
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OptionRules
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.PhoneOrders
import de.hamzabistro.printstation.core.Selection
import de.hamzabistro.printstation.core.TillState

// Tapping dishes: the menu, the basket, and the sales still open.

const val TILL_RING_UP_TAG = "till-ring-up"

/** One dish to tap. */
const val TILL_TILE_TAG = "till-tile"

/** One line of the basket. */
const val TILL_LINE_TAG = "till-line"

/** Where the basket is. */
internal enum class TillLayout {
    /** Beside the dishes: the tablet, lying. */
    BESIDE,

    /** Below them: the tablet, standing. */
    BELOW,

    /** A tap away: a phone. */
    AWAY;

    companion object {
        fun of(width: Dp, height: Dp): TillLayout =
            when {
                width >= TILL_WIDE_FROM -> BESIDE
                width >= 600.dp && height >= 700.dp -> BELOW
                width >= 720.dp -> BESIDE
                else -> AWAY
            }
    }
}

/** What a basket line, a dish and an amount are called by: read at arm's length. */
private val NAME = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, hyphens = Hyphens.Auto)
private val SMALL = TextStyle(fontSize = 15.sp, lineHeight = 19.sp)

@Composable
internal fun TillDishesPane(state: TillState, view: TillView, photos: Map<String, ImageBitmap?>, model: TillActions) {
    var picking by remember { mutableStateOf<MenuDish?>(null) }
    var basketUp by rememberSaveable { mutableStateOf(false) }
    var openUp by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    // The next customer starts with the dishes.
    LaunchedEffect(state.lines.isEmpty()) { if (state.lines.isEmpty()) basketUp = false }
    LaunchedEffect(state.open.isEmpty()) { if (state.open.isEmpty()) openUp = false }
    val menu = view.menu
    val tap: (MenuDish, List<OptionGroup>) -> Unit = { dish, groups ->
        // Nothing to choose: in the basket with one tap.
        if (groups.isEmpty()) model.add(BasketLine(dish, 1)) else picking = dish
    }
    val clear = { clearing = true }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (TillLayout.of(maxWidth, maxHeight)) {
            TillLayout.BESIDE -> {
                val basket = if (maxWidth >= 1000.dp) 432.dp else maxWidth * 0.42f
                Row(Modifier.fillMaxSize()) {
                    TillShop(state, view, photos, model, rail = true, onTap = tap, onOpenSales = { openUp = true }, modifier = Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    TillBasket(state, model, onClear = clear, modifier = Modifier.width(basket).fillMaxHeight())
                }
            }
            TillLayout.BELOW -> {
                val basket = maxHeight * 0.45f
                Column(Modifier.fillMaxSize()) {
                    TillShop(state, view, photos, model, rail = true, onTap = tap, onOpenSales = { openUp = true }, modifier = Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider()
                    TillBasketBelow(state, model, onClear = clear, modifier = Modifier.height(basket).fillMaxWidth())
                }
            }
            TillLayout.AWAY -> {
                val up = basketUp && state.lines.isNotEmpty()
                BackHandler(enabled = up) { basketUp = false }
                Column(Modifier.fillMaxSize()) {
                    if (up) {
                        TextButton(onClick = { basketUp = false }, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.till_dishes), style = MaterialTheme.typography.titleMedium)
                        }
                        state.problem?.let { TillProblemCard(it, model::dismissProblem, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                        TillBasket(state, model, onClear = clear, modifier = Modifier.weight(1f).fillMaxWidth())
                    } else {
                        TillShop(state, view, photos, model, rail = false, onTap = tap, onOpenSales = { openUp = true }, modifier = Modifier.weight(1f).fillMaxWidth())
                        HorizontalDivider()
                        TillToBasket(state) { basketUp = true }
                    }
                }
            }
        }
        val dish = picking
        if (dish != null && menu != null) {
            val groups = remember(dish, menu) { OptionRules.groups(dish, menu.groups) }
            TillOptions(dish, groups, onDismiss = { picking = null }) {
                model.add(it)
                picking = null
            }
        }
        if (openUp && state.open.isNotEmpty()) TillOpenSales(state, onResume = model::resume, onDismiss = { openUp = false })
    }
    if (clearing && state.lines.isNotEmpty()) {
        TillAsk(stringResource(R.string.till_clear_title), null, stringResource(R.string.till_clear), onDismiss = { clearing = false }) {
            clearing = false
            model.clear()
        }
    }
}

// ---------------------------------------------------------------------------
// The menu
// ---------------------------------------------------------------------------

/** The dishes, over them the sales still open, and over both what went wrong: laid on top, so no tile moves away under a finger. */
@Composable
private fun TillShop(
    state: TillState,
    view: TillView,
    photos: Map<String, ImageBitmap?>,
    model: TillActions,
    rail: Boolean,
    onTap: (MenuDish, List<OptionGroup>) -> Unit,
    onOpenSales: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            TillOpenBar(state, model, onOpenSales)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    view.menu != null -> TillMenu(view.menu, state.lines, photos, model::loadPhoto, rail, onTap)
                    view.menuError != null ->
                        Column(
                            Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                stringResource(R.string.new_order_menu_failed, view.menuError),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            BigButton(stringResource(R.string.retry), onClick = model::loadMenu, outlined = true)
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
        state.problem?.let { TillProblemCard(it, model::dismissProblem, Modifier.align(Alignment.BottomCenter).padding(12.dp)) }
    }
}

/** A category at a time: the categories down the side, all in sight, or along the top where there is no side. */
@Composable
private fun TillMenu(
    menu: OrderMenu,
    lines: List<BasketLine>,
    photos: Map<String, ImageBitmap?>,
    onPhoto: (String) -> Unit,
    rail: Boolean,
    onTap: (MenuDish, List<OptionGroup>) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val dishes = remember(menu) { menu.dishes.filterNot { it.archived } }
    val categories = remember(dishes) { listOf<String?>(null) + dishes.map { it.category }.distinct() }
    val shown = dishes.filter { category == null || it.category == category }
    val grid: @Composable (Modifier) -> Unit = { modifier ->
        TillGrid(shown, menu, lines, photos, onPhoto, rail, onTap, modifier)
    }
    if (rail) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.width(140.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (name in categories) TillCategory(name, category == name, Modifier.fillMaxWidth()) { category = name }
            }
            grid(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (name in categories) TillCategory(name, category == name, Modifier) { category = name }
            }
            grid(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun TillCategory(name: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
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
private fun TillGrid(
    dishes: List<MenuDish>,
    menu: OrderMenu,
    lines: List<BasketLine>,
    photos: Map<String, ImageBitmap?>,
    onPhoto: (String) -> Unit,
    rail: Boolean,
    onTap: (MenuDish, List<OptionGroup>) -> Unit,
    modifier: Modifier,
) {
    // One height for all, two lines of name and the price whatever the font size: the tiles line up, photo or not.
    val height = with(LocalDensity.current) { 78.dp + (NAME.lineHeight * 2).toDp() + PRICE_LINE.toDp() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = if (rail) 8.dp else 0.dp, bottom = 8.dp),
        modifier = modifier,
    ) {
        items(dishes, key = { it.id }) { dish ->
            val groups = remember(dish, menu.groups) { OptionRules.groups(dish, menu.groups) }
            val count = lines.filter { it.dish.id == dish.id }.sumOf { it.qty }
            LaunchedEffect(dish.imageUrl) { onPhoto(dish.imageUrl) }
            TillTile(dish, photos[dish.imageUrl], OptionRules.orderable(dish, groups), count, Modifier.height(height)) { onTap(dish, groups) }
        }
    }
}

private val PRICE_LINE = 24.sp

@Composable
private fun TillTile(dish: MenuDish, photo: ImageBitmap?, orderable: Boolean, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = orderable,
        colors =
            if (count > 0) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth().alpha(if (orderable) 1f else 0.5f).testTag(TILL_TILE_TAG),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (photo != null) {
                Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().weight(1f))
            }
            Column(
                if (photo != null) Modifier.padding(horizontal = 10.dp, vertical = 6.dp) else Modifier.weight(1f).padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(dish.name, style = NAME, maxLines = if (photo != null) 2 else 4, overflow = TextOverflow.Ellipsis)
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
 * them. What can be chosen is [OptionRules]', as in the phone order's panel.
 */
@Composable
private fun TillOptions(dish: MenuDish, groups: List<OptionGroup>, onDismiss: () -> Unit, onAdd: (BasketLine) -> Unit) {
    var chosen by remember(dish.id) { mutableStateOf(OptionRules.preset(groups)) }
    var qty by remember(dish.id) { mutableIntStateOf(1) }
    val line = BasketLine(dish, qty, OptionRules.chosen(groups, chosen))
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= TILL_WIDE_FROM
            val roomy = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(dish.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    val columns = if (wide && groups.size > 1) groups.chunked((groups.size + 1) / 2) else listOf(groups)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        for (column in columns) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                for (group in column) {
                                    TillOptionGroup(group, chosen[group.id].orEmpty()) { chosen = OptionRules.toggle(group, chosen, it) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                val stepper: @Composable () -> Unit = { TillStepper(qty, dish.name) { qty = it.coerceIn(1, PhoneOrders.MAX_QTY) } }
                // A phone has no room for it in the bar.
                if (!roomy) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.new_order_qty), fontSize = 18.sp, modifier = Modifier.weight(1f))
                        stepper()
                    }
                }
                BottomBar {
                    BigButton(stringResource(R.string.cancel_back), onClick = onDismiss, outlined = true)
                    if (roomy) stepper()
                    Button(
                        onClick = { onAdd(line) },
                        enabled = OptionRules.invalid(groups, chosen).isEmpty(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    ) {
                        TillButtonWords(stringResource(R.string.call_order_add), Format.euro(line.unitPrice * qty), big = false)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TillOptionGroup(group: OptionGroup, chosen: List<Long>, onToggle: (MenuOption) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val hint = stringResource(if (group.selection == Selection.SINGLE) R.string.new_order_single_hint else R.string.new_order_multiple_hint)
        Text("${group.name} · $hint", fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in group.options) {
                val on = option.id in chosen
                val label = buildString {
                    if (on) append("✓ ")
                    append(option.name)
                    if (option.price > 0) append(" +").append(Format.euro(option.price))
                }
                Surface(
                    selected = on,
                    onClick = { onToggle(option) },
                    enabled = option.available,
                    shape = MaterialTheme.shapes.medium,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alpha(if (option.available) 1f else 0.5f),
                ) {
                    Column(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                        Text(label, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                        if (!option.available) Text(stringResource(R.string.new_order_sold_out), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The basket
// ---------------------------------------------------------------------------

/** A column: where it is eaten on top, the lines, and "Kassieren" below, always in reach. */
@Composable
private fun TillBasket(state: TillState, model: TillActions, onClear: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        TillWhere(state, model, onClear, Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp))
        TillLines(state, model, Modifier.weight(1f).fillMaxWidth())
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TillDeal(state)
            TillRingUp(state, model, Modifier.fillMaxWidth())
        }
    }
}

/** Across the whole width: the lines above, everything else in one row. */
@Composable
private fun TillBasketBelow(state: TillState, model: TillActions, onClear: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        TillLines(state, model, Modifier.weight(1f).fillMaxWidth())
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TillWhere(state, model, onClear, Modifier.weight(1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TillDeal(state)
                TillRingUp(state, model, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Mitnehmen or hier essen, and everything out again. */
@Composable
private fun TillWhere(state: TillState, model: TillActions, onClear: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            listOf(false to R.string.till_takeaway, true to R.string.till_dine_in).forEachIndexed { index, (dineIn, label) ->
                SegmentedButton(
                    selected = state.dineIn == dineIn,
                    onClick = { model.setDineIn(dineIn) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                    label = { Text(stringResource(label), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 52.dp),
                )
            }
        }
        val words = stringResource(R.string.till_clear)
        IconButton(onClick = onClear, enabled = state.lines.isNotEmpty() && !state.busy, modifier = Modifier.size(52.dp)) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = words)
        }
    }
}

@Composable
private fun TillLines(state: TillState, model: TillActions, modifier: Modifier) {
    if (state.lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.till_empty), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val list = rememberLazyListState()
    FollowTheTap(state.lines, list)
    LazyColumn(modifier, state = list) {
        itemsIndexed(state.lines, key = { _, line -> "${line.dish.id}:${line.options.joinToString(",") { it.id.toString() }}" }) { index, line ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            TillLine(line) { model.setQty(index, it) }
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
private fun TillStepper(qty: Int, dish: String, onQty: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TillQty("−", stringResource(R.string.till_one_less, dish)) { onQty(qty - 1) }
        Text(
            qty.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        TillQty("+", stringResource(R.string.till_one_more, dish)) { onQty(qty + 1) }
    }
}

/** How many, of what, for how much — one row, its buttons big. */
@Composable
private fun TillLine(line: BasketLine, onQty: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 3.dp).testTag(TILL_LINE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TillStepper(line.qty, line.dish.name, onQty)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(line.dish.name, style = NAME, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(Format.euro(line.unitPrice * line.qty), style = NAME, maxLines = 1)
            }
            if (line.options.isNotEmpty()) {
                Text(line.options.joinToString(", ") { it.name }, style = SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TillQty(text: String, words: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(48.dp).semantics { contentDescription = words }) {
        Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** The deal of the day, once the database has taken it off. */
@Composable
private fun TillDeal(state: TillState) {
    val deal = state.price?.dealDiscount ?: 0.0
    if (!state.priced || deal <= 0 || state.lines.isEmpty()) return
    Row {
        Text(stringResource(R.string.new_order_deal), fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(Format.euro(-deal), fontSize = 17.sp)
    }
}

/** The total and the button are one: there is nothing to look for. */
@Composable
private fun TillRingUp(state: TillState, model: TillActions, modifier: Modifier) {
    Button(
        onClick = model::ringUp,
        enabled = state.lines.isNotEmpty() && !state.busy,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 72.dp).testTag(TILL_RING_UP_TAG),
    ) {
        TillButtonWords(stringResource(R.string.till_ring_up), Format.euro(CounterSales.euros(state.totalCents)))
    }
}

/** A button's words at its left, its amount, bigger, at its right. */
@Composable
private fun RowScope.TillButtonWords(text: String, amount: String, big: Boolean = true) {
    Text(text, fontSize = if (big) 22.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(8.dp))
    Text(amount, fontSize = if (big) 28.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/** On a phone, under the dishes: what is in the basket, and on to it. */
@Composable
private fun TillToBasket(state: TillState, onClick: () -> Unit) {
    val count = state.lines.sumOf { it.qty }
    Button(
        onClick = onClick,
        enabled = count > 0,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 64.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.till_basket, count, count, Format.euro(CounterSales.euros(state.totalCents))),
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.till_to_basket), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// The sales still open
// ---------------------------------------------------------------------------

/** One line for the sales somebody has to see to: red when one is from an earlier day, or its card was asked. */
@Composable
private fun TillOpenBar(state: TillState, model: TillActions, onShow: () -> Unit) {
    if (state.open.isEmpty()) return
    val one = state.open.singleOrNull()
    val stale = state.open.any { it.stale }
    val card = state.open.any { it.cardPending }
    Surface(
        onClick = { if (one != null) model.resume(one) else onShow() },
        enabled = !state.busy,
        shape = MaterialTheme.shapes.medium,
        color = if (stale || card) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (stale || card) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val what =
                when {
                    one == null -> pluralStringResource(R.plurals.till_open_count, state.open.size, state.open.size)
                    one.training -> stringResource(R.string.till_open_training, one.number, Format.euro(one.total))
                    else -> stringResource(R.string.till_open_sale, one.number, Format.euro(one.total))
                }
            val why =
                when {
                    stale -> stringResource(R.string.till_open_stale_short)
                    card -> stringResource(R.string.till_open_card_short)
                    else -> null
                }
            Text(
                listOfNotNull(what, why).joinToString(" · "),
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (one != null) R.string.till_resume else R.string.till_open_show), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** All of them, over the whole screen: which, since when, where, and what is the matter with it. */
@Composable
private fun TillOpenSales(state: TillState, onResume: (CounterSale) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.weight(1f).widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.till_open_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                for (sale in state.open) OpenSaleCard(sale, enabled = !state.busy) { onResume(sale) }
            }
            HorizontalDivider()
            BottomBar { BigButton(stringResource(R.string.cancel_back), onClick = onDismiss, outlined = true) }
        }
    }
}

/** A sale rung up and not finished, here or on another device. */
@Composable
internal fun OpenSaleCard(sale: CounterSale, enabled: Boolean, onResume: () -> Unit) {
    Card(
        onClick = onResume,
        enabled = enabled,
        colors =
            if (sale.stale || sale.cardPending) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (sale.training) R.string.till_open_training else R.string.till_open_sale, sale.number, Format.euro(sale.total)),
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                val at = sale.openedAt?.let { if (sale.stale) Format.day(it) + " " + Format.clock(it) else Format.clock(it) }.orEmpty()
                Text(
                    listOfNotNull(at.ifEmpty { null }, sale.openedOn?.ifEmpty { null }, sale.openedBy.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (sale.cardPending) Text(stringResource(R.string.till_card_pending), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (sale.stale) Text(stringResource(R.string.till_open_stale), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.till_resume), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
