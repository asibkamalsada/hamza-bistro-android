package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            )
        check(made.size == 10)
    }

    @Test
    fun theStaffAppDraws() {
        val staff = StaffViewModel(app)
        val main = MainViewModel(app)
        val focus = MutableStateFlow<String?>(null)
        compose.setContent { AppTheme { StaffApp(staff, main, focus, onFocused = {}) } }
        compose.waitForIdle()
        // ☰ opens the drawer (android#38); a notification tapped closes it again.
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Recent orders").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        // The queue, then the two screens a notification opens.
        for (asked in listOf(ISSUES_FOCUS, HISTORY_FOCUS)) {
            focus.value = asked
            compose.waitForIdle()
        }
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
                StaffDrawer(drawer, enabled = true, issues, DrawerHeader("kitchen@example.com", "Tablet Küche", "9.9"), onOpen = { opened = it }) {
                    TopAppBar(title = { Text("Queue") }, navigationIcon = { MenuButton(issues.count, onClick = {}) })
                }
            }
        }
        for (entry in listOf("Recent orders", "Complaints", "Cash-up", "Reports", "Menu", "Delivery hours", "Settings")) {
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
