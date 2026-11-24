package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import de.hamzabistro.printstation.core.PhoneOrderDraft
import de.hamzabistro.printstation.core.RequestedLink
import de.hamzabistro.printstation.core.Selection
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An order called in: the name, the address or a pickup, the dishes as tiles
 * — and the baskets that wait above the queue for their caller's address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h2400dp") // a tablet, tall enough for each step at once
class TakingAnOrderTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun open(): NewOrderViewModel {
        val model = NewOrderViewModel(app)
        compose.setContent { AppTheme { NewOrderScreen(onBack = {}, navigation = {}, model = model) } }
        compose.waitForIdle()
        return model
    }

    private fun named(model: NewOrderViewModel) {
        compose.onNodeWithText("Name").performTextInput("Ali")
        compose.onNode(hasText("Phone") and hasSetTextAction()).performTextInput("0176 5550001")
        compose.waitForIdle()
        assertEquals("Ali", model.state.value.draft.name)
    }

    @Test
    fun aCallIsAPhoneDeliveryTakenInTwoSteps() {
        val model = open()
        assertEquals(OrderSource.PHONE, model.state.value.draft.source)
        assertEquals(Fulfilment.DELIVERY, model.state.value.draft.fulfilment)
        compose.onNodeWithText("Step 1 of 2 · For whom, and where to?").assertIsDisplayed()
        named(model)
        // The way out for an address that does not survive the phone.
        compose.onNodeWithText("📱 Send the caller an SMS").assertIsDisplayed().assertIsEnabled()

        compose.onNodeWithText("Street and number").performTextInput("karl heine 9")
        compose.waitUntil(timeoutMillis = 10_000) { model.state.value.streets.isNotEmpty() }
        compose.onNodeWithText("Karl-Heine-Straße 9").performClick()
        compose.waitForIdle()
        assertEquals("04229", model.state.value.draft.postalCode)

        compose.onNodeWithText("Dishes ➜").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Step 2 of 2 · Which dishes?").assertIsDisplayed()
        assertEquals(TakingStep.DISHES, model.state.value.step)
        // Whose order it is, and the way back to it.
        compose.onNodeWithText("Karl-Heine-Straße 9, 04229").performClick()
        compose.waitForIdle()
        assertEquals(TakingStep.CUSTOMER, model.state.value.step)
    }

    @Test
    @Config(qualifiers = "w360dp-h420dp") // a phone with its keyboard up
    fun theStreetToPickComesIntoSightAsItIsTyped() {
        val model = open()
        compose.onNodeWithText("Street and number").performScrollTo().performTextInput("karl heine 9")
        compose.waitUntil(timeoutMillis = 10_000) { model.state.value.streets.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Karl-Heine-Straße 9").assertIsDisplayed()
        compose.onNodeWithText("karl heine 9").assertIsDisplayed()
    }

    @Test
    fun theDishesComeFirstWhenTheCallerStartsWithThem() {
        val model = open()
        compose.onNodeWithText("Dishes ➜").performClick()
        model.add(BasketLine(cola, 1))
        compose.waitForIdle()
        compose.onNodeWithText("Still missing: name, phone, street, postcode (5 digits) ➜").performClick()
        compose.waitForIdle()
        assertEquals(TakingStep.CUSTOMER, model.state.value.step)
        assertEquals(1, model.state.value.draft.lines.size)
    }

    @Test
    fun aLandlineIsNotOfferedAnSms() {
        open()
        compose.onNode(hasText("Phone") and hasSetTextAction()).performTextInput("0341 5550001")
        compose.waitForIdle()
        compose.onNodeWithText("📱 Send the caller an SMS").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("That is a landline number: an SMS only goes to a mobile.").assertIsDisplayed()
    }

    @Test
    fun aCallerWhoPicksUpNeedsNoAddress() {
        val model = open()
        named(model)
        compose.onNodeWithText("Pickup").performClick()
        compose.waitForIdle()
        assertEquals(Fulfilment.PICKUP, model.state.value.draft.fulfilment)
        compose.onNodeWithText("Street and number").assertDoesNotExist()
        compose.onNodeWithText("📱 Send the caller an SMS").assertDoesNotExist()
    }

    @Test
    fun somebodyAtTheCounterIsAnOrderOfTheirs() {
        val model = open()
        compose.onNodeWithText("Counter").performClick()
        compose.onNodeWithText("Eat in").performClick()
        compose.waitForIdle()
        assertEquals(OrderSource.COUNTER, model.state.value.draft.source)
        assertEquals(Fulfilment.DINE_IN, model.state.value.draft.fulfilment)
        // Chosen stays chosen: only a new order starts as a phone delivery again.
        model.start()
        compose.waitForIdle()
        assertEquals(OrderSource.COUNTER, model.state.value.draft.source)
        model.another()
        compose.waitForIdle()
        assertEquals(OrderSource.PHONE, model.state.value.draft.source)
        assertEquals(Fulfilment.DELIVERY, model.state.value.draft.fulfilment)
    }

    @Test
    fun anSmsSentWaitsForTheAddressUnlessItWasTypedAfterAll() {
        val link = RequestedLink("r-1", "0123456789abcdef0123456789abcdef")
        val texted = NewOrderState(draft = PhoneOrderDraft(fulfilment = Fulfilment.DELIVERY), link = link)
        assertTrue(texted.awaitingLink)
        // Spelled out after all: saved as typed, the link closed with it.
        val typed = texted.copy(draft = texted.draft.copy(street = "Lützner Str. 9", postalCode = "04177"))
        assertFalse(typed.awaitingLink)
        // Arrived through the link: in the fields, priced as typed.
        assertFalse(texted.copy(linkAddressAt = Instant.now()).awaitingLink)
        // A pickup has no address to wait for.
        assertFalse(texted.copy(draft = PhoneOrderDraft(fulfilment = Fulfilment.PICKUP)).awaitingLink)
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
                val shop = Shop(OrderMenu(listOf(doener, cola, soldOut), listOf(meat)), null, emptyList(), emptyMap(), onPhoto = {}, onRetry = {})
                DishesOrWhyNot(shop, listOf(BasketLine(cola, 2)), rail = false, onTap = { dish, groups -> tapped += dish.name to groups.size }, Modifier)
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
