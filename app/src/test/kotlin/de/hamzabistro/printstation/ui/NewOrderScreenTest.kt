package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.StreetDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A delivery's street in "Neue Bestellung", typed the way it was heard and
 * picked from Leipzig's streets — the list as built into the app, so this
 * also says that it is in the build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h2400dp") // tall enough for the whole form at once
class NewOrderScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The form with "Lieferung" picked and [street] typed, once the list has been searched for it. */
    private fun typed(street: String): NewOrderViewModel {
        val model = NewOrderViewModel(app)
        compose.setContent { AppTheme { NewOrderScreen(onBack = {}, navigation = {}, model = model) } }
        compose.onNodeWithText("Delivery").performClick()
        compose.onNodeWithText("Street and number").performTextInput(street)
        // Searched off the main thread, and read from the build the first time.
        compose.waitUntil(timeoutMillis = 10_000) { model.state.value.let { it.streets.isNotEmpty() || it.streetUnlisted } }
        return model
    }

    @Test
    fun aStreetTypedByEarIsPickedWithItsPostcodeAndTown() {
        val model = typed("karl heine 9")
        compose.onNodeWithText("Karl-Heine-Straße 9").assertIsDisplayed().performClick()
        compose.waitForIdle()
        val draft = model.state.value.draft
        assertEquals("Karl-Heine-Straße 9", draft.street)
        assertEquals("04229", draft.postalCode)
        assertEquals("Leipzig", draft.city)
        // Taken: nothing is left to pick, and the other Karl-Heine is gone.
        assertTrue(model.state.value.streetChoices.isEmpty())
        compose.onNodeWithText("Karl-Heine-Platz 9").assertDoesNotExist()
    }

    @Test
    fun aStreetNobodyRidesToSaysSoBeforeItIsPicked() {
        val model = typed("karl herting 9")
        assertEquals("Karl-Härting-Straße 9", model.state.value.streets.first().fullStreet)
        assertEquals(StreetDelivery.OUTSIDE, model.state.value.streets.first().entry.delivery)
        compose.onNodeWithText("Karl-Härting-Straße 9").assertIsDisplayed()
        compose.onAllNodesWithText("no delivery").onFirst().assertIsDisplayed()
    }

    @Test
    fun theDrawerLeadsAwayFromAnOrderHalfTypedOnlyOnceItIsDiscarded() {
        val model = NewOrderViewModel(app)
        val leaving = Leaving()
        compose.setContent { AppTheme { NewOrderScreen(onBack = {}, navigation = {}, leaving = leaving, model = model) } }
        model.add(BasketLine(MenuDish(1, "Speisen", "Döner", "", 7.5, 12, 0, "", true, emptyList()), 1))
        compose.waitForIdle()
        var gone = false
        compose.runOnIdle { leaving.leave { gone = true } }
        compose.onNodeWithText("Discard this order?").assertIsDisplayed()
        assertFalse(gone)
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        assertTrue(gone)
        assertTrue(model.state.value.draft.lines.isEmpty())
    }

    @Test
    fun aDishesNoteIsTypedOnItsLine() {
        val model = NewOrderViewModel(app)
        compose.setContent { AppTheme { NewOrderScreen(onBack = {}, navigation = {}, model = model) } }
        val doener = MenuDish(1, "Speisen", "Döner", "", 7.5, 12, 0, "", true, emptyList())
        model.add(BasketLine(doener, 1))
        compose.waitForIdle()
        compose.onNodeWithText("+ Note").performClick()
        compose.onNodeWithText("Note for this dish (optional)").performTextInput("ohne Zwiebeln")
        compose.onNodeWithText("Add ✓").performClick()
        compose.waitForIdle()
        assertEquals("ohne Zwiebeln", model.state.value.draft.lines.single().note)
        compose.onNodeWithText("„ohne Zwiebeln“").assertIsDisplayed()
        // Read again meanwhile, and dearer: still one line for the two without a note.
        model.add(BasketLine(doener, 1))
        model.add(BasketLine(doener.copy(price = 8.0), 1))
        compose.waitForIdle()
        assertEquals(listOf(1, 2), model.state.value.draft.lines.map { it.qty })
    }

    @Test
    fun aStreetTheListHasNothingLikeStaysAsTyped() {
        val model = typed("Qwertzuiopweg 3")
        assertTrue(model.state.value.streetChoices.isEmpty())
        compose.onNodeWithText("No street in Leipzig", substring = true).assertIsDisplayed()
        assertEquals("Qwertzuiopweg 3", model.state.value.draft.street)
    }
}
