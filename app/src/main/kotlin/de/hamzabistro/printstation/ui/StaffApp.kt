package de.hamzabistro.printstation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hamzabistro.printstation.BuildConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where a staff account is in the app. ☰ leads from each to the others; back, to the queue. */
enum class StaffScreen {
    QUEUE,

    /** "Letzte Bestellungen" and today's takings. */
    HISTORY,

    /** "Reklamationen": the customers' open problem reports, answered with a voucher or not. */
    ISSUES,

    /** The Kassensturz: per driver, cash and card for a day. */
    CASH_UP,

    /** The Auswertung: sales, best sellers, busy hours, times over a range of days. */
    REPORT,

    /** What is sold out, and what a dish is and costs: the site's /menu-admin. */
    MENU,

    /** Open or closed, closures planned ahead, the week: the site's /orders/hours. */
    HOURS,

    /** This device, who hears about orders, the printers, the address check. */
    SETTINGS,

    /** "Kasse": a sale at the counter, rung up, paid and signed (hamza-bistro-web#225). */
    TILL,

    /** "Neue Bestellung": a phone or walk-in order (#9), with the address by SMS when it does not survive the phone. */
    NEW_ORDER,
}

/**
 * A staff account's app: the queue, and from it everything the website's
 * staff pages had — the history, the menu, the delivery hours, the settings —
 * in the drawer that ☰ opens, the same on every one of them.
 */
@Composable
fun StaffApp(staff: StaffViewModel, main: MainViewModel, focus: StateFlow<String?>, onFocused: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(StaffScreen.QUEUE) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val back = { screen = StaffScreen.QUEUE }
    val leaving = remember { Leaving() }
    if (screen != StaffScreen.QUEUE) BackHandler(onBack = back)
    // "Neue Reklamation" tapped: the list, not the queue; "Schlechte
    // Bewertung": the history, where the stars are.
    val asked by focus.collectAsStateWithLifecycle()
    LaunchedEffect(asked) {
        val target =
            when (asked) {
                ISSUES_FOCUS -> StaffScreen.ISSUES
                HISTORY_FOCUS -> StaffScreen.HISTORY
                else -> return@LaunchedEffect
            }
        screen = target
        // From the notification shade, the drawer may still be open.
        drawer.snapTo(DrawerValue.Closed)
        onFocused()
    }
    val issues by staff.issues.collectAsStateWithLifecycle()
    val account = main.state.collectAsStateWithLifecycle().value.account
    val prefs = staff.state.collectAsStateWithLifecycle().value.prefs
    val label = prefs.label
    val update = staff.update.collectAsStateWithLifecycle().value.available
    val install by staff.installing.collectAsStateWithLifecycle()
    val header =
        remember(account, label, update, install) {
            DrawerHeader(account?.email, staff.deviceLabel(), BuildConfig.VERSION_NAME, update, install)
        }
    // Whether there is a till, and what is open on it: once signed in, and whenever the drawer is opened.
    // Not over the till: it looks itself, and a look that failed would replace what it says about a sale.
    val till by staff.till.collectAsStateWithLifecycle()
    LaunchedEffect(account?.userId, drawer.isOpen) { if (account != null && screen != StaffScreen.TILL) staff.lookAtTill() }
    val navigation = @Composable { MenuButton(issues.count, update = update != null) { scope.launch { drawer.open() } } }
    StaffDrawer(
        drawer,
        current = screen,
        issues,
        header,
        onOpen = { to -> if (to != screen) leaving.leave { screen = to } },
        onInstall = { staff.install(it) },
        till = till,
    ) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                StaffScreen.QUEUE -> QueueScreen(staff, focus, onFocused, onOpen = { screen = it }, navigation = navigation)
                StaffScreen.HISTORY -> HistoryScreen(staff, navigation)
                StaffScreen.ISSUES -> IssuesScreen(navigation)
                StaffScreen.CASH_UP -> CashUpScreen(navigation)
                StaffScreen.REPORT -> ReportScreen(navigation)
                StaffScreen.MENU -> MenuScreen(navigation)
                StaffScreen.HOURS -> HoursScreen(staff, navigation)
                StaffScreen.SETTINGS -> SettingsScreen(staff, main, navigation)
                StaffScreen.TILL -> TillScreen(onBack = back, navigation = navigation, leaving = leaving)
                StaffScreen.NEW_ORDER -> NewOrderScreen(onBack = back, navigation = navigation, leaving = leaving)
            }
            // Who is calling the shop's phone, over any screen. Not on a driver's phone: nobody takes an order there.
            if (!prefs.driver) {
                CallBanners(
                    newOrderOpen = screen == StaffScreen.NEW_ORDER,
                    onNewOrder = { screen = StaffScreen.NEW_ORDER },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/**
 * What the screen shown does, or asks, before the drawer leads away from it:
 * an order half typed is not discarded unasked. A screen with nothing to say
 * is simply left.
 */
class Leaving {
    private var guard: ((() -> Unit) -> Unit)? = null

    /** Leaves with [go]: at once, or when the screen says so. */
    fun leave(go: () -> Unit) {
        val asked = guard
        if (asked == null) go() else asked(go)
    }

    /** From the screen, for as long as it is shown: [ask] is given what leaves it. */
    @Composable
    fun Ask(ask: (() -> Unit) -> Unit) {
        val latest by rememberUpdatedState(ask)
        DisposableEffect(this) {
            val mine: (() -> Unit) -> Unit = { go -> latest(go) }
            guard = mine
            // The next screen's may be in place already.
            onDispose { if (guard === mine) guard = null }
        }
    }
}

/** What a notification asks to focus to open "Reklamationen" rather than an order. */
const val ISSUES_FOCUS = "#issues"

/** What a notification asks to focus to open "Letzte Bestellungen". */
const val HISTORY_FOCUS = "#history"
