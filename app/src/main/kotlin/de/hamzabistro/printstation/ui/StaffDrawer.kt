package de.hamzabistro.printstation.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge as CountBadge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AppRelease
import de.hamzabistro.printstation.core.IssuesState
import kotlinx.coroutines.launch

/**
 * Who and what the drawer's header names: the account signed in, this
 * device, the build — and a newer build when there is one (android#40),
 * rather than a line above the queue.
 */
data class DrawerHeader(val account: String?, val device: String, val version: String, val update: AppRelease? = null)

/**
 * Where the queue leads, in a drawer from the left: opened with ☰ or a swipe
 * from the left edge, closed with back, a tap beside it or a choice. The
 * same modal drawer on a tablet as on a phone: the queue's three columns
 * keep the whole width, and the screens behind it are rarely needed while
 * orders come in. "Reklamationen" only on a database that has them, with
 * the number open on it — and on ☰ too ([MenuButton]).
 */
@Composable
fun StaffDrawer(
    drawer: DrawerState,
    enabled: Boolean,
    issues: IssuesState,
    header: DrawerHeader,
    onOpen: (StaffScreen) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawer,
        // Only where ☰ is, so a sub-screen's own swipes are its own; while
        // open, always, so it can be swiped shut.
        gesturesEnabled = enabled || drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Header(header)
                    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                    for (entry in drawerEntries(issues)) {
                        val count = if (entry.screen == StaffScreen.ISSUES) issues.count else 0
                        NavigationDrawerItem(
                            icon = { Icon(painterResource(entry.icon), contentDescription = null) },
                            label = { Text(stringResource(entry.label)) },
                            badge = { if (count > 0) CountBadge { Text(count.toString()) } },
                            selected = false,
                            onClick = {
                                scope.launch { drawer.close() }
                                onOpen(entry.screen)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        },
        content = content,
    )
    // Composed after the screens' own, so back closes the drawer first.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
}

/**
 * ☰, with the number of open Reklamationen on its corner while there are
 * any, else a dot while an update waits in the drawer's header.
 */
@Composable
fun MenuButton(open: Int, update: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        val label = stringResource(if (update) R.string.menu_open_update else R.string.menu_open)
        val icon = @Composable { Icon(painterResource(R.drawable.ic_menu), contentDescription = label) }
        when {
            open > 0 -> BadgedBox(badge = { CountBadge { Text(open.toString()) } }) { icon() }
            update -> BadgedBox(badge = { CountBadge() }) { icon() }
            else -> icon()
        }
    }
}

@Composable
private fun Header(header: DrawerHeader) {
    Column(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.drawer_shop), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        header.account?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(stringResource(R.string.drawer_device, header.device), style = MaterialTheme.typography.bodySmall, color = muted)
        Text(stringResource(R.string.app_version, header.version), style = MaterialTheme.typography.bodySmall, color = muted)
        header.update?.let { release ->
            val context = LocalContext.current
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.update_available, release.versionName), style = MaterialTheme.typography.titleSmall)
            Button(onClick = { context.openDownload(release.downloadUrl) }, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.update_install))
            }
        }
    }
}

private class DrawerEntry(val screen: StaffScreen, @StringRes val label: Int, @DrawableRes val icon: Int)

private fun drawerEntries(issues: IssuesState): List<DrawerEntry> =
    listOfNotNull(
        DrawerEntry(StaffScreen.HISTORY, R.string.history_title, R.drawable.ic_history),
        DrawerEntry(StaffScreen.ISSUES, R.string.issues_title, R.drawable.ic_issues).takeIf { issues.available == true },
        DrawerEntry(StaffScreen.CASH_UP, R.string.cash_up_title, R.drawable.ic_cash_up),
        DrawerEntry(StaffScreen.REPORT, R.string.report_title, R.drawable.ic_report),
        DrawerEntry(StaffScreen.MENU, R.string.menu_title, R.drawable.ic_menu_card),
        DrawerEntry(StaffScreen.HOURS, R.string.hours_title, R.drawable.ic_hours),
        DrawerEntry(StaffScreen.SETTINGS, R.string.settings, R.drawable.ic_settings),
    )
