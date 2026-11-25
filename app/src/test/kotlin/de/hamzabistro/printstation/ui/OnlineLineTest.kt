package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import de.hamzabistro.printstation.core.CancelReason
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.PaymentMethod
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.station.DevicePrefs
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a card says about the money of an order paid online (hamza-bistro-web#82). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlineLineTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-11-25T18:05:00Z")

    private val order =
        StaffOrder(
            id = "a",
            orderNumber = 57,
            createdAt = now.minusSeconds(60),
            customerName = "Frau Mustermann",
            total = 23.4,
            status = OrderStatus.NEW,
            returningCustomer = false,
        )

    private val none =
        object : OrderActions {
            override fun accept(order: StaffOrder, minutes: Int) = Unit

            override fun acceptScheduled(order: StaffOrder) = Unit

            override fun moveOn(order: StaffOrder) = Unit

            override fun deliver(order: StaffOrder, payment: PaymentMethod) = Unit

            override fun cancel(order: StaffOrder, reason: CancelReason?) = Unit

            override fun resetNoShows(order: StaffOrder, done: (NoShowReset) -> Unit) = Unit

            override fun delay(order: StaffOrder, minutes: Int) = Unit

            override fun pack(order: StaffOrder) = Unit

            override fun unpack(order: StaffOrder) = Unit

            override fun undo(order: StaffOrder) = Unit

            override fun print(order: StaffOrder) = Unit

            override fun call(phone: String) = Unit

            override fun route(order: StaffOrder) = Unit
        }

    private fun draw(order: StaffOrder) {
        compose.setContent {
            AppTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OrderCard(order, now, emptyMap(), DevicePrefs(), pending = null, busy = false, canPrint = false, actions = none)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()

    private fun absent(text: String) = compose.onNodeWithText(text, substring = true).assertDoesNotExist()

    @Test
    fun heldItSaysToCollectNothingAndNotToCall() {
        draw(order.copy(paymentMethod = PaymentMethod.ONLINE))
        shown("ONLINE – collect nothing")
        shown("Total ")
        absent("Collect ")
        absent("New customer")
    }

    @Test
    fun lostItSaysToCollectAndWhatAndAsksForTheCallAgain() {
        draw(order.copy(onlineLostAt = now))
        shown("Online hold gone – collect the money")
        shown("Collect ")
        shown("New customer")
    }

    @Test
    fun atTheDoorNothingIsDifferent() {
        draw(order)
        shown("Collect ")
        shown("New customer")
        absent("ONLINE")
        absent("Online")
    }

    @Test
    fun heldOnTheWayThereIsOneButtonAndNoCashOrCard() {
        draw(order.copy(status = OrderStatus.ON_THE_WAY, paymentMethod = PaymentMethod.ONLINE))
        shown("Delivered")
        absent("Cash")
        absent("Card")
    }
}
