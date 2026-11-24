package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import de.hamzabistro.printstation.core.IssuesState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What runs the moment a staff account is signed in: the view models made
 * and the screens drawn. The release that closed after sign-in (android#24)
 * failed in StaffViewModel's constructor, which no test made until now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun everyViewModelIsMadeWithoutFailing() {
        val made: List<ViewModel> =
            listOf(
                StaffViewModel(app),
                MainViewModel(app),
                NewOrderViewModel(app),
                HealthViewModel(app),
                HoursViewModel(app),
                HistoryViewModel(app),
                IssuesViewModel(app),
                MenuViewModel(app),
                CashUpViewModel(app),
                ReportViewModel(app),
                TillViewModel(app),
            )
        check(made.size == 11)
    }

    @Test
    fun theStaffAppDraws() {
        val staff = StaffViewModel(app)
        val main = MainViewModel(app)
        val focus = MutableStateFlow<String?>(null)
        compose.setContent { AppTheme { StaffApp(staff, main, focus, onFocused = {}) } }
        compose.waitForIdle()
        // ☰ opens the drawer (android#38); a notification tapped closes it again.
        // A newer release on GitHub, if the check reached it, sits in the
        // drawer's header and names itself on ☰ (android#40).
        compose.onNodeWithContentDescription("Open menu", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Recent orders").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
        // The queue, then the two screens a notification opens.
        for (asked in listOf(ISSUES_FOCUS, HISTORY_FOCUS)) {
            focus.value = asked
            compose.waitForIdle()
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun everyScreenHasTheSameMenuAndItLeadsToEveryOther() {
        val staff = StaffViewModel(app)
        val main = MainViewModel(app)
        compose.setContent { AppTheme { StaffApp(staff, main, MutableStateFlow(null), onFocused = {}) } }
        compose.waitForIdle()
        for (label in listOf("Recent orders", "Cash-up", "Reports", "Menu", "Delivery hours", "Settings", "Orders")) {
            openMenu()
            entry(label).performScrollTo().performClick()
            compose.waitForIdle()
            // There, says the drawer, opened with that screen's ☰.
            openMenu()
            entry(label).assertIsDisplayed().assertIsSelected().performClick()
            compose.waitForIdle()
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun backClosesTheMenuBeforeItLeavesTheScreen() {
        val staff = StaffViewModel(app)
        val main = MainViewModel(app)
        var back: OnBackPressedDispatcher? = null
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AppTheme { StaffApp(staff, main, MutableStateFlow(null), onFocused = {}) }
        }
        compose.waitForIdle()
        // A screen with a back of its own.
        compose.onNodeWithText("+ New order").performClick()
        compose.waitForIdle()
        openMenu()
        entry("Orders").assertIsDisplayed()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.waitForIdle()
        entry("Orders").assertIsNotDisplayed()
        compose.onNodeWithText("New order").assertIsDisplayed()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(STRIP_TAG).assertIsDisplayed()
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription("Open menu", substring = true).performClick()
        compose.waitForIdle()
    }

    /** The drawer's entry, not the title of the screen it leads to. */
    private fun entry(label: String) = compose.onNode(hasText(label) and isSelectable())

    @Test
    fun theQueueStripOpensItsSheet() {
        val staff = StaffViewModel(app)
        val main = MainViewModel(app)
        compose.setContent { AppTheme { StaffApp(staff, main, MutableStateFlow(null), onFocused = {}) } }
        compose.waitForIdle()
        // The status strip (android#40), on the real view model: the sheet with the notices opens.
        compose.onNodeWithTag(STRIP_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Notices").assertExists()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    @Config(qualifiers = "w411dp-h891dp") // a phone; Robolectric's default screen is smaller than any
    fun theDrawerShowsEveryEntryAndTheOpenComplaints() {
        val issues = IssuesState(available = true, ids = listOf("a", "b", "c"), count = 3)
        var opened: StaffScreen? = null
        compose.setContent {
            AppTheme {
                val drawer = rememberDrawerState(DrawerValue.Open)
                StaffDrawer(drawer, current = StaffScreen.QUEUE, issues, DrawerHeader("kitchen@example.com", "Tablet Küche", "9.9"), onOpen = { opened = it }) {
                    TopAppBar(title = { Text("Queue") }, navigationIcon = { MenuButton(issues.count, onClick = {}) })
                }
            }
        }
        for (entry in listOf("Orders", "Recent orders", "Complaints", "Cash-up", "Reports", "Menu", "Delivery hours", "Settings")) {
            compose.onNodeWithText(entry).assertIsDisplayed()
        }
        compose.onNodeWithText("Hamza Bistro").assertIsDisplayed()
        compose.onNodeWithText("kitchen@example.com").assertIsDisplayed()
        compose.onNodeWithText("Tablet Küche", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Version 9.9").assertIsDisplayed()
        // The count on "Reklamationen" and on ☰.
        compose.onAllNodesWithText("3").assertCountEquals(2)
        compose.onNodeWithContentDescription("Open menu").assertExists()
        compose.onNodeWithText("Complaints").performClick()
        compose.waitForIdle()
        check(opened == StaffScreen.ISSUES)
    }

    @Test
    fun theCrashScreenShowsTheTraceAndGoesOn() {
        var gone = false
        compose.setContent { AppTheme { CrashScreen("java.lang.NullPointerException: boom\n\tat Somewhere") { gone = true } } }
        compose.onNodeWithText("java.lang.NullPointerException", substring = true).assertExists()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Copied").assertExists()
        compose.onNodeWithText("Continue").performClick()
        check(gone)
    }
}
