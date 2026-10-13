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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A house number typed after the street, in "Neue Bestellung": on a long
 * street it has its own postcode and ring, from the list as built into the
 * app — and one the list does not have is said to be so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h2400dp") // tall enough for the whole form at once
class HouseNumberChoicesTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun typed(street: String): NewOrderViewModel {
        val model = NewOrderViewModel(app)
        compose.setContent { AppTheme { NewOrderScreen(onBack = {}, model = model) } }
        compose.onNodeWithText("Delivery").performClick()
        compose.onNodeWithText("Street and number").performTextInput(street)
        // Searched off the main thread, and read from the build the first time.
        compose.waitUntil(timeoutMillis = 10_000) { model.state.value.streets.isNotEmpty() }
        return model
    }

    @Test
    fun aHouseFarDownALongStreetHasItsOwnPostcode() {
        // Lützner Straße runs through three postcodes; most of it is 04177, number 150 is not.
        val model = typed("lutzner 150")
        compose.onNodeWithText("04179 · Altlindenau · Inner").assertIsDisplayed()
        compose.onNodeWithText("Lützner Straße 150").performClick()
        compose.waitForIdle()
        assertEquals("Lützner Straße 150", model.state.value.draft.street)
        assertEquals("04179", model.state.value.draft.postalCode)
    }

    @Test
    fun aNumberTheListDoesNotHaveIsSaidAndStillTaken() {
        val model = typed("lutzner 9999")
        // On every street offered whose numbers the list has; the first is Lützner Straße.
        compose.onAllNodesWithText("No. 9999 is not on the list for this street").onFirst().assertIsDisplayed()
        // The street's own postcodes, as before there were house numbers.
        compose.onNodeWithText("04177, 04179, 04205 · Altlindenau").assertIsDisplayed()
        compose.onNodeWithText("Lützner Straße 9999").performClick()
        compose.waitForIdle()
        assertEquals("Lützner Straße 9999", model.state.value.draft.street)
        assertEquals("04177", model.state.value.draft.postalCode)
    }
}
