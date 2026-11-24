package de.hamzabistro.printstation.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.hamzabistro.printstation.core.AddressRequest
import de.hamzabistro.printstation.core.Fulfilment
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One picture of an order being taken: what it shows, and what is tapped before it is taken. */
private class OrderShot(
    val name: String,
    val state: NewOrderState,
    val link: AddressRequest? = null,
    val keyboard: Boolean = false,
    val night: Boolean = false,
    val on: (TillDevice) -> Boolean = { true },
    val act: ComposeContentTestRule.(TillDevice) -> Unit = {},
)

private fun ComposeContentTestRule.toBasket() = onNodeWithText("Warenkorb", substring = true).performClick()

private val shots: List<OrderShot> by lazy {
    val f = OrderFixtures
    fun pickup(state: NewOrderState) = state.copy(draft = state.draft.copy(fulfilment = Fulfilment.PICKUP))
    val later = Instant.parse("2026-11-24T18:30:00Z")
    val refused = "Der Preis hat sich geändert. Bitte den neuen Preis prüfen und erneut speichern."
    listOf(
        OrderShot("order-01-start", f.called, keyboard = true),
        OrderShot("order-02-start-no-keyboard", f.called),
        OrderShot("order-03-typing", f.typing, keyboard = true, act = { onNode(hasText("Straße und Hausnummer") and hasSetTextAction()).performClick() }),
        OrderShot("order-04-typing-no-keyboard", f.typing),
        OrderShot("order-05-known", f.known),
        OrderShot("order-06-found", f.addressed),
        OrderShot("order-07-found-keyboard", f.addressed, keyboard = true),
        OrderShot("order-08-outside", f.outside),
        OrderShot("order-09-texted", f.texted, f.link()),
        OrderShot("order-10-text-failed", f.texted, f.link(error = "RESULT_ERROR_NO_SERVICE")),
        OrderShot("order-11-pickup", pickup(f.priced(3))),
        OrderShot("order-12-later", f.addressed.copy(draft = f.addressed.draft.copy(scheduledFor = later, waiveFees = true, notes = "Bitte alles getrennt einpacken"))),
        OrderShot("order-20-dishes-empty", f.at(TakingStep.DISHES, f.addressed)),
        OrderShot("order-21-dishes", f.at(TakingStep.DISHES, f.noted(5))),
        OrderShot("order-22-dishes-twelve", f.at(TakingStep.DISHES, f.priced(12))),
        OrderShot("order-23-basket-up", f.at(TakingStep.DISHES, f.noted(6)), on = { it.phone }, act = { toBasket() }),
        OrderShot("order-24-options", f.at(TakingStep.DISHES, f.priced(3)), act = { onAllNodesWithText("Dürüm Döner").onFirst().performClick() }),
        OrderShot("order-25-nothing-known", f.at(TakingStep.DISHES, f.with(2, f.called)), act = { if (it.phone) toBasket() }),
        OrderShot("order-26-pickup", f.at(TakingStep.DISHES, pickup(f.with(3))), act = { if (it.phone) toBasket() }),
        OrderShot("order-27-texted", f.at(TakingStep.DISHES, f.with(3, f.texted)), f.link(), act = { if (it.phone) toBasket() }),
        OrderShot("order-28-outside", f.at(TakingStep.DISHES, f.with(3, f.outside)), act = { if (it.phone) toBasket() }),
        OrderShot("order-29-refused", f.at(TakingStep.DISHES, f.with(3)).copy(error = refused), act = { if (it.phone) toBasket() }),
        OrderShot("order-30-pricing", f.at(TakingStep.DISHES, f.with(3)).copy(quoting = true), act = { if (it.phone) toBasket() }),
        OrderShot("order-31-check-failed", f.at(TakingStep.DISHES, f.with(3)).copy(addressError = "Keine Verbindung zum Server."), act = { if (it.phone) toBasket() }),
        OrderShot("order-32-texted-later", f.at(TakingStep.DISHES, f.with(3, f.texted).let { it.copy(draft = it.draft.copy(scheduledFor = later)) }), f.link(), act = { if (it.phone) toBasket() }),
        OrderShot("order-33-kitchen-full", f.at(TakingStep.DISHES, f.priced(3).let { it.copy(quote = it.quote?.copy(overCapacity = true, smallOrderFee = 2.0, waived = 1.5)) }), act = { if (it.phone) toBasket() }),
        OrderShot("order-40-done", f.saved()),
        OrderShot("order-41-done-waiting", f.waiting(), f.link()),
        OrderShot("order-42-done-problem", f.waiting(), f.link(problem = "HB426")),
        OrderShot("order-43-menu-failed", f.at(TakingStep.DISHES, f.addressed.copy(menu = null, menuError = "Keine Verbindung zum Server."))),
        OrderShot("order-44-dark", f.at(TakingStep.DISHES, f.noted(5)), night = true, on = { it == TillDevice.TABLET_LANDSCAPE }),
        OrderShot("order-45-dark-customer", f.known, night = true, on = { it == TillDevice.TABLET_LANDSCAPE }),
    )
}

/**
 * The pictures of "Neue Bestellung", every state on every screen,
 * for somebody to look at: build/kasse-shots, which CI keeps as an artifact.
 * What must hold is in [OrderLayoutTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class OrderShotsTest(private val device: TillDevice, private val name: String) {
    private val shot = shots.first { it.name == name }
    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(OnDevice(device, shot.night)).around(compose)

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun draw() {
        compose.setScreen(if (shot.keyboard) device.keyboard else null) {
            val navigation = @androidx.compose.runtime.Composable { MenuButton(0, onClick = {}) }
            NewOrderContent(shot.state, TillFixtures.photos, shot.link, NoOrder(), {}, {}, {}, navigation)
        }
        shot.act(compose, device)
        compose.waitForIdle()
        captureScreenRoboImage(File("build/kasse-shots", "$name--${device.name.lowercase().replace('_', '-')}.png"))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{1} {0}")
        fun all(): List<Array<Any>> = TillDevice.entries.flatMap { device -> shots.filter { it.on(device) }.map { arrayOf(device, it.name) } }
    }
}
