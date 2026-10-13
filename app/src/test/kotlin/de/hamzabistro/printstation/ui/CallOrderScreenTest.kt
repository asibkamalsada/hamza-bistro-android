package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import de.hamzabistro.printstation.core.AddressRequest
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.Fulfilment
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.MenuOption
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.OrderSource
import de.hamzabistro.printstation.core.Selection
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Telefon-Bestellung": a call taken one big step at a time — the name, the
 * address or a pickup, the dishes as tiles — and the baskets that wait above
 * the queue for their caller's address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h2400dp") // a tablet, tall enough for each step at once
class CallOrderScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun open(): NewOrderViewModel {
        val model = NewOrderViewModel(app)
        compose.setContent { AppTheme { CallOrderScreen(onBack = {}, onFullForm = {}, model = model) } }
        compose.waitForIdle()
        return model
    }

    private fun named(model: NewOrderViewModel) {
        compose.onNodeWithText("Next ➜").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("Ali")
        compose.onNodeWithText("Phone number").performTextInput("0176 5550001")
        compose.onNodeWithText("Next ➜").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("Ali", model.state.value.draft.name)
    }

    @Test
    fun aCallIsAPhoneDeliveryTakenStepByStep() {
        val model = open()
        assertEquals(OrderSource.PHONE, model.state.value.draft.source)
        assertEquals(Fulfilment.DELIVERY, model.state.value.draft.fulfilment)
        compose.onNodeWithText("What is the caller's name?").assertIsDisplayed()
        named(model)

        compose.onNodeWithText("Where should it go?").assertIsDisplayed()
        // The way out for an address that does not survive the phone.
        compose.onNodeWithText("📱 Send the caller an SMS").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Next ➜").assertIsNotEnabled()

        compose.onNodeWithText("Street and house number").performTextInput("karl heine 9")
        compose.waitUntil(timeoutMillis = 10_000) { model.state.value.streets.isNotEmpty() }
        compose.onNodeWithText("Karl-Heine-Straße 9").performClick()
        compose.waitForIdle()
        assertEquals("04229", model.state.value.draft.postalCode)

        compose.onNodeWithText("Next ➜").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Which dishes?").assertIsDisplayed()
        assertEquals(CallStep.DISHES, model.state.value.step)
    }

    @Test
    fun aCallerWhoPicksUpNeedsNoAddress() {
        val model = open()
        named(model)
        compose.onNodeWithText("🛍 Picks it up (no address)").performClick()
        compose.waitForIdle()
        assertEquals(Fulfilment.PICKUP, model.state.value.draft.fulfilment)
        compose.onNodeWithText("🛍 Picks it up at the shop").assertIsDisplayed()
        compose.onNodeWithText("Next ➜").assertIsEnabled()
    }

    // -----------------------------------------------------------------------
    // The dishes as tiles
    // -----------------------------------------------------------------------

    private val meat = OptionGroup(10, "Fleisch", listOf(MenuOption(101, "Hähnchen", 0.0, true, emptyList())), listOf("Döner"), Selection.SINGLE)
    private val doener = MenuDish(1, "Speisen", "Döner", "", 7.5, 12, 0, "", true, emptyList(), groupIds = listOf(10))
    private val cola = MenuDish(3, "Getränke", "Cola", "", 2.25, 0, 0, "", true, emptyList())
    private val soldOut = MenuDish(4, "Getränke", "Ayran", "", 2.0, 0, 0, "", false, emptyList())

    @Test
    fun aDishIsATileThatSaysHowManyAreInTheBasket() {
        val tapped = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            AppTheme {
                DishGrid(OrderMenu(listOf(doener, cola, soldOut), listOf(meat)), listOf(BasketLine(cola, 2)), emptyMap(), onPhoto = {}) { dish, groups ->
                    tapped += dish.name to groups.size
                }
            }
        }
        compose.onNodeWithText("2×").assertIsDisplayed()
        compose.onNodeWithText("Cola").performClick()
        compose.onNodeWithText("Döner").performClick()
        // Sold out: there, and not to be tapped.
        compose.onNodeWithText("Ayran").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Cola" to 0, "Döner" to 1), tapped)

        // A category at a time.
        compose.onNodeWithText("Getränke").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Döner").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------
    // Waiting for the address, above the queue
    // -----------------------------------------------------------------------

    private fun waiting(id: String, problem: String? = null, error: String? = null) =
        AddressRequest(
            id = id,
            token = "0123456789abcdef0123456789abcdef",
            phone = "+491765550001",
            customerName = "Ali",
            createdAt = Instant.now().minusSeconds(120),
            smsError = error,
            savedAt = Instant.now(),
            problem = problem,
        )

    @Test
    fun aBasketWaitingForItsAddressSaysWhyAndOffersWhatHelps() {
        val retried = mutableListOf<String>()
        val resent = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        compose.setContent {
            AppTheme {
                WaitingCards(listOf(waiting("outside", problem = "HB426"), waiting("failed", error = "RESULT_ERROR_NO_SERVICE")), resent::add, retried::add, deleted::add)
            }
        }
        compose.onNodeWithText("Waiting for an address · 2").assertIsDisplayed()
        compose.onNodeWithText("✗ The address is outside the delivery area. Call the customer.").assertIsDisplayed()
        compose.onNodeWithText("✗ SMS not sent: RESULT_ERROR_NO_SERVICE").assertIsDisplayed()

        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Send again").performClick()
        compose.waitForIdle()
        assertEquals(listOf("outside"), retried)
        assertEquals(listOf("failed"), resent)
        assertTrue(deleted.isEmpty())
    }
}
