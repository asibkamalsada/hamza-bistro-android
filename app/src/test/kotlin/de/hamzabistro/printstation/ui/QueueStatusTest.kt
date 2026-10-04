package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.core.AppRelease
import de.hamzabistro.printstation.core.FailedEmail
import de.hamzabistro.printstation.core.IssuesState
import de.hamzabistro.printstation.core.Kitchen
import de.hamzabistro.printstation.core.KitchenSlot
import de.hamzabistro.printstation.core.PauseWhat
import de.hamzabistro.printstation.core.ShopHours
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The top of the queue (android#40): one strip and the blocking bars, the
 * rest a tap away in a sheet. On a 360×800 phone, under a 64 dp app bar and
 * 48 dp tabs, the orders start within the top fifth with nothing wrong, and
 * above the middle with everything wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
// Real text measuring: the heights below are what a phone draws.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueStatusTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-04T16:05:00Z")
    private val open = ShopView(ShopHours(openNow = true, openUntil = now.plus(Duration.ofHours(4))))
    private val kitchen = listOf(KitchenSlot(Kitchen.slotOf(now), capacity = 4, dishes = 2))

    private val calls = mutableListOf<String>()
    private val actions =
        StatusActions(
            shop = RecordingShop(calls),
            startShift = { calls += "startShift" },
            retry = { calls += "retry" },
            delayAll = { calls += "delayAll" },
            openOrder = { calls += "order $it" },
            open = { calls += "open $it" },
        )

    private fun draw(status: QueueStatus) {
        compose.setContent {
            AppTheme {
                Column(Modifier.fillMaxSize()) {
                    QueueStatusTop(status, actions)
                    Box(Modifier.fillMaxSize().testTag(LIST))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun listTop() = compose.onNodeWithTag(LIST).getUnclippedBoundsInRoot().top

    @Test
    fun withNothingWrongTheStripIsAllAndTheControlsAreOneTapAway() {
        draw(QueueStatus(shop = open, now = now, kitchen = kitchen, delayable = 3))
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("Kitchen ■■□□").assertIsDisplayed()
        compose.onNodeWithText("notice", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Offline", substring = true).assertDoesNotExist()
        // App bar 64 + strip + tabs 48 within a fifth of 800.
        val top = listTop()
        check(top + 64.dp + 48.dp <= 160.dp) { "orders start at $top under the app bar" }

        compose.onNodeWithTag(STRIP_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pause…").assertIsDisplayed()
        compose.onNodeWithText("Busy…").assertIsDisplayed()
        compose.onNodeWithText("Kitchen, next hour").assertIsDisplayed()
        compose.onNodeWithText("Delivery hours").assertIsDisplayed()
        compose.onNodeWithText("All +15 min").performClick()
        compose.onNodeWithText("Yes", substring = true).performClick()
        compose.waitForIdle()
        check("delayAll" in calls) { calls.toString() }
        // Pausing goes through the same two taps as before.
        compose.onNodeWithText("Pause…").performClick()
        compose.onNodeWithText("Delivery only").performClick()
        compose.onNodeWithText("30 min", substring = true).performClick()
        compose.waitForIdle()
        check("pause 30 ${PauseWhat.DELIVERY}" in calls) { calls.toString() }
    }

    @Test
    fun withEveryWarningTheBarsStayAndTheRestIsInTheSheet() {
        draw(
            QueueStatus(
                shop = ShopView(open.hours!!.copy(busyExtraMinutes = 15, busyUntil = now.plus(Duration.ofHours(1)))),
                now = now,
                kitchen = kitchen,
                delayable = 3,
                notStaff = true,
                offlineSince = now.minus(Duration.ofMinutes(5)),
                alarmMissing = listOf("Notifications"),
                silencedUntil = now.plus(Duration.ofMinutes(10)),
                addressFailing = true,
                failedEmail = FailedEmail(orderNumber = 57, error = "bounced", failedAt = now),
                notifyFailingSince = now.minus(Duration.ofMinutes(30)),
            )
        )
        compose.onNodeWithText("Open · Busy +15").assertIsDisplayed()
        compose.onNodeWithText("⚠ 5 notices").assertIsDisplayed()
        compose.onNodeWithText("Not a staff account — ask the owner").assertIsDisplayed()
        compose.onNodeWithText("Offline since 18:00").assertIsDisplayed()
        val top = listTop()
        check(top + 64.dp + 48.dp < 400.dp) { "orders start at $top under the app bar" }
        compose.onNodeWithText("Retry").performClick()
        check("retry" in calls)

        compose.onNodeWithText("⚠ 5 notices").performClick()
        compose.waitForIdle()
        for (
            text in
                listOf(
                    "The alarm is limited — missing: Notifications",
                    "Alarm silenced until 18:15",
                    "not (or no longer) on the staff list",
                    "address check is not working",
                    "No connection to the shop since 18:00",
                    "Notifications disrupted since 17:35",
                    "Pause…",
                    "All +15 min",
                )
        ) {
            compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
        }
        // The email's warning still leads to its order.
        compose.onNodeWithText("The email for #57", substring = true).performScrollTo().performClick()
        compose.waitForIdle()
        check("order 57" in calls) { calls.toString() }
    }

    @Test
    fun offShiftTheBarStartsTheShift() {
        draw(QueueStatus(shop = open, now = now, onShift = false, alarmMissing = listOf("Notifications")))
        compose.onNodeWithText("Not on shift — no alarm").assertIsDisplayed()
        // Off shift the alarm's permissions are not a notice of their own.
        compose.onNodeWithText("notice", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Start shift").performClick()
        check("startShift" in calls)
    }

    @Test
    fun anUpdateIsInTheDrawerWithADotOnTheMenu() {
        val release = AppRelease(versionCode = 200, versionName = "0.2.200", downloadUrl = "https://example.com/app.apk")
        compose.setContent {
            AppTheme {
                val drawer = rememberDrawerState(DrawerValue.Open)
                StaffDrawer(drawer, enabled = true, IssuesState(), DrawerHeader(null, "Tablet", "0.2.100", release), onOpen = {}) {
                    Column {
                        MenuButton(0, update = true, onClick = {})
                        Text("Queue")
                    }
                }
            }
        }
        compose.onNodeWithText("Update available: 0.2.200").assertIsDisplayed()
        compose.onNodeWithText("Install").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open menu — an update is available").assertExists()
    }

    private class RecordingShop(val calls: MutableList<String>) : ShopActions {
        override fun pause(minutes: Long, what: PauseWhat) {
            calls += "pause $minutes $what"
        }

        override fun closeForToday(what: PauseWhat) {
            calls += "today $what"
        }

        override fun closeForGood(what: PauseWhat) {
            calls += "good $what"
        }

        override fun open() {
            calls += "open"
        }

        override fun busy(minutes: Int, duration: Duration?) {
            calls += "busy $minutes"
        }

        override fun notBusy() {
            calls += "notBusy"
        }
    }

    private companion object {
        const val LIST = "list"
    }
}
