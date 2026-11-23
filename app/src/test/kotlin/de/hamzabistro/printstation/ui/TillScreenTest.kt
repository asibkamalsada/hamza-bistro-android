package de.hamzabistro.printstation.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.CounterError
import de.hamzabistro.printstation.core.CounterSale
import de.hamzabistro.printstation.core.CounterStatus
import de.hamzabistro.printstation.core.IssuesState
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.TillProblem
import de.hamzabistro.printstation.core.TillState
import de.hamzabistro.printstation.core.TillStep
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Kasse": drawn for a tablet and a phone, and where the drawer leads to it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1800dp")
class TillScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val till = (app as PrintStationApp).graph.till

    private val doener = MenuDish(13, "Döner", "Döner", "", 7.5, 12, 0, "", true, emptyList())
    private val cola = MenuDish(48, "Getränke", "Cola", "", 2.5, 0, 0, "", true, emptyList(), deposit = 0.25)

    @After fun empty() = till.clear()

    private fun open() {
        val model = TillViewModel(app)
        compose.setContent { AppTheme { TillScreen(onBack = {}, model = model) } }
        compose.waitForIdle()
    }

    @Test
    fun theBasketStandsBesideTheDishesAndAddsUp() {
        till.add(BasketLine(doener, 2))
        till.add(BasketLine(cola, 1))
        open()
        compose.onNodeWithText("Döner").assertIsDisplayed()
        compose.onNodeWithTag(TILL_RING_UP_TAG).assertIsDisplayed().assertIsEnabled().assertTextContains(Format.euro(17.5))
        compose.onNodeWithText("Eat here").performClick()
        compose.waitForIdle()
        assertTrue(till.state.value.dineIn)
        compose.onNodeWithText("Eat here").assertIsSelected()
        compose.onNodeWithContentDescription("One Döner more").performClick()
        compose.waitForIdle()
        assertEquals(3, till.state.value.lines.first().qty)
        // Asked first: the bin is beside the other buttons.
        compose.onNodeWithContentDescription("Empty the basket").performClick()
        compose.waitForIdle()
        assertEquals(2, till.state.value.lines.size)
        compose.onNodeWithText("Empty the basket").performClick()
        compose.waitForIdle()
        assertTrue(till.state.value.lines.isEmpty())
        compose.onNodeWithTag(TILL_RING_UP_TAG).assertIsNotEnabled()
    }

    @Test
    fun withNobodySignedInNothingIsSoldAndTheScreenSaysSo() {
        till.add(BasketLine(doener, 1))
        open()
        till.dismissProblem()
        compose.waitForIdle()
        compose.onNodeWithTag(TILL_RING_UP_TAG).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            // The answer comes back to the main thread, which this test is on.
            shadowOf(Looper.getMainLooper()).idle()
            till.state.value.problem != null && !till.state.value.busy
        }
        compose.waitForIdle()
        assertEquals(TillStep.DISHES, till.state.value.step)
        assertEquals(null, till.state.value.sale)
        compose.onNodeWithText(tillProblemText(app, till.state.value.problem!!)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp") // a phone
    fun onAPhoneTheBasketIsOneTapAway() {
        till.add(BasketLine(doener, 2))
        open()
        compose.onNodeWithText("2 dishes").assertIsDisplayed().assertTextContains(Format.euro(15.0))
        compose.onNodeWithText("Basket ➜").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TILL_RING_UP_TAG).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Takeaway").assertIsDisplayed()
    }

    @Test
    fun anOpenSaleSaysWhatItIsAndGoesOnWithATap() {
        var resumed = false
        val sale =
            CounterSale(
                "s-1", 321, "open", total = 16.0, pendingMethod = "card", openedAt = Instant.parse("2026-11-22T11:00:00Z"),
                openedBy = "koch", openedOn = "Tablet", stale = true,
            )
        compose.setContent { AppTheme { OpenSaleCard(sale, enabled = true) { resumed = true } } }
        compose.onNodeWithText("Open sale #321 · " + Format.euro(16.0)).assertIsDisplayed()
        compose.onNodeWithText("22.11. 12:00 · Tablet · koch").assertIsDisplayed()
        compose.onNodeWithText("A card payment was started: check the card app").assertIsDisplayed()
        compose.onNodeWithText("From an earlier day: finish or abort it").assertIsDisplayed()
        compose.onNodeWithText("Go on ➜").performClick()
        compose.waitForIdle()
        assertTrue(resumed)
    }

    @Test
    fun theReceiptIsAQrCode() {
        compose.setContent { AppTheme { QrImage("https://www.hamzabistro.de/beleg/0123456789abcdef0123456789abcdef", "QR") } }
        compose.onNodeWithContentDescription("QR").assertIsDisplayed()
    }

    @Test
    fun theKeysTypeAnAmountAndNothingElse() {
        fun typed(keys: String, from: String = "") = keys.fold(from) { given, key -> tillTyped(given, key) }
        assertEquals("20", typed("20"))
        assertEquals("12,5", typed("12,5"))
        // A third digit after the comma, a second comma: not taken.
        assertEquals("12,50", typed("12,,505"))
        assertEquals("0,5", typed(",5"))
        assertEquals("5", typed("05"))
        assertEquals("1", typed("⌫⌫", from = "123"))
        assertEquals("", typed("⌫", from = ""))
        assertEquals("1234", typed("12345"))
    }

    @Test
    fun everyProblemHasItsOwnWords() {
        val problems =
            CounterError.entries.map { TillProblem.Refused(it) } +
                listOf(TillProblem.Offline(true), TillProblem.Offline(false), TillProblem.SignedOut, TillProblem.Unavailable, TillProblem.Other("x"))
        val words = problems.map { tillProblemText(app, it) }
        assertEquals(words.size, words.toSet().size)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun theDrawerHasTheTillOnlyOnADatabaseThatHasOne() {
        var opened: StaffScreen? = null
        val shown = TillState(available = true, status = CounterStatus(true, 2, 0), open = listOf(CounterSale("a", 1, "open"), CounterSale("b", 2, "open")))
        compose.setContent {
            AppTheme {
                StaffDrawer(
                    rememberDrawerState(DrawerValue.Open),
                    enabled = true,
                    IssuesState(),
                    DrawerHeader("a@example.com", "Tablet", "9.9"),
                    onOpen = { opened = it },
                    till = shown,
                ) {
                    Text("Queue")
                }
            }
        }
        compose.onNodeWithText("Till").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("Till").performClick()
        compose.waitForIdle()
        assertEquals(StaffScreen.TILL, opened)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun theDrawerHasNoTillUntilTheDatabaseSaysSo() {
        compose.setContent {
            AppTheme {
                StaffDrawer(
                    rememberDrawerState(DrawerValue.Open),
                    enabled = true,
                    IssuesState(),
                    DrawerHeader("a@example.com", "Tablet", "9.9"),
                    onOpen = {},
                ) {
                    Text("Queue")
                }
            }
        }
        compose.onNodeWithText("Cash-up").assertIsDisplayed()
        compose.onNodeWithText("Till").assertDoesNotExist()
    }
}
