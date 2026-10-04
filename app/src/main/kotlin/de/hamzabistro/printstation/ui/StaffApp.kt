package de.hamzabistro.printstation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Where a staff account is in the app. Everything but the queue goes back to the queue. */
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
}

/**
 * A staff account's app: the queue, and from it everything the website's
 * staff pages had — the history, the menu, the delivery hours, the settings.
 */
@Composable
fun StaffApp(staff: StaffViewModel, main: MainViewModel, focus: StateFlow<String?>, onFocused: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(StaffScreen.QUEUE) }
    val back = { screen = StaffScreen.QUEUE }
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
        onFocused()
    }
    when (screen) {
        StaffScreen.QUEUE -> QueueScreen(staff, focus, onFocused, onOpen = { screen = it })
        StaffScreen.HISTORY -> HistoryScreen(staff, onBack = back)
        StaffScreen.ISSUES -> IssuesScreen(onBack = back)
        StaffScreen.CASH_UP -> CashUpScreen(onBack = back)
        StaffScreen.REPORT -> ReportScreen(onBack = back)
        StaffScreen.MENU -> MenuScreen(onBack = back)
        StaffScreen.HOURS -> HoursScreen(staff, onBack = back)
        StaffScreen.SETTINGS -> SettingsScreen(staff, main, onBack = back)
    }
}

/** What a notification asks to focus to open "Reklamationen" rather than an order. */
const val ISSUES_FOCUS = "#issues"

/** What a notification asks to focus to open "Letzte Bestellungen". */
const val HISTORY_FOCUS = "#history"
