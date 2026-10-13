package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import de.hamzabistro.printstation.core.IncomingCall
import de.hamzabistro.printstation.core.OrderSource
import de.hamzabistro.printstation.core.RecentAddress
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The caller's number over the staff app: what the banner says, and what its buttons do to "Neue Bestellung". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallBannerTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val order = NewOrderViewModel(app)

    private var opened = 0

    private fun call(id: String, phone: String, secondsAgo: Long = 5) = IncomingCall(id, phone, Instant.now().minusSeconds(secondsAgo))

    private fun banners(calls: List<IncomingCall>, newOrderOpen: Boolean = false, lastAddress: RecentAddress? = null) {
        compose.setContent { AppTheme { CallBanners(calls, lastAddress = { lastAddress }, newOrderOpen = newOrderOpen, onNewOrder = { opened++ }, order = order) } }
        compose.waitForIdle()
    }

    @Test
    fun theCallersNumberOpensTheCallOrderWithItFilledIn() {
        banners(listOf(call("c-1", "+491761234567")), lastAddress = RecentAddress("Karl-Heine-Straße 9", "04229", "Leipzig", "Hinterhaus"))
        compose.onNodeWithText("Call from 0176 1234567").assertIsDisplayed()
        compose.onNodeWithText("last delivery: Karl-Heine-Straße 9, 04229 Leipzig").assertIsDisplayed()
        // Nothing has opened or been filled in by itself.
        assertEquals(0, opened)
        assertEquals("", order.state.value.draft.phone)

        compose.onNodeWithText("Take the order").performClick()
        compose.waitForIdle()

        assertEquals(1, opened)
        assertEquals("0176 1234567", order.state.value.draft.phone)
        assertEquals(OrderSource.PHONE, order.state.value.draft.source)
        // Used: the banner is gone.
        compose.onNodeWithText("Call from 0176 1234567").assertDoesNotExist()
    }

    @Test
    fun withTheFormOpenTheNumberGoesOnlyIntoAnEmptyPhoneField() {
        order.edit { it.copy(phone = "0341 999999") }
        banners(listOf(call("c-1", "+491761234567")), newOrderOpen = true)
        compose.onNodeWithText("Call from 0176 1234567").assertIsDisplayed()
        // A number is typed already: the banner can only be put away.
        compose.onNodeWithText("Use number").assertDoesNotExist()
        compose.onNodeWithText("Take the order").assertDoesNotExist()

        order.edit { it.copy(phone = "") }
        compose.waitForIdle()
        compose.onNodeWithText("Use number").performClick()
        compose.waitForIdle()

        assertEquals("0176 1234567", order.state.value.draft.phone)
        assertEquals(1, opened)
    }

    @Test
    fun aBannerIsPutAwayAndAnOldCallIsNotShown() {
        banners(listOf(call("new", "+491761234567"), call("old", "+4934112345678", secondsAgo = 200)))
        // More than three minutes ago: not that caller any more.
        compose.onNodeWithText("Call from 034112345678").assertDoesNotExist()
        // No address on record: the banner without the line.
        compose.onNodeWithText("last delivery", substring = true).assertDoesNotExist()

        compose.onNodeWithContentDescription("Put away").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Call from 0176 1234567").assertDoesNotExist()
        assertEquals(0, opened)
        assertTrue(order.state.value.draft.phone.isEmpty())
    }
}
