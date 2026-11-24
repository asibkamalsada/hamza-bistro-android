package de.hamzabistro.printstation.ui

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
    /** "Neue Bestellung", not Telefon-Bestellung. */
    val fullForm: Boolean = false,
    val keyboard: Boolean = false,
    val night: Boolean = false,
    val on: (TillDevice) -> Boolean = { true },
    val act: ComposeContentTestRule.(TillDevice) -> Unit = {},
)

private val shots: List<OrderShot> by lazy {
    val f = OrderFixtures
    fun pickup(state: NewOrderState) = state.copy(draft = state.draft.copy(fulfilment = Fulfilment.PICKUP))
    listOf(
        OrderShot("call-01-name", f.called, keyboard = true),
        OrderShot("call-02-address", f.at(CallStep.ADDRESS)),
        OrderShot("call-03-address-typing", f.at(CallStep.ADDRESS, f.typing), keyboard = true),
        OrderShot("call-03-address-typing-no-keyboard", f.at(CallStep.ADDRESS, f.typing)),
        OrderShot("call-04-address-known", f.at(CallStep.ADDRESS, f.known)),
        OrderShot("call-05-address-found", f.at(CallStep.ADDRESS, f.addressed)),
        OrderShot("call-06-address-outside", f.at(CallStep.ADDRESS, f.outside)),
        OrderShot("call-07-address-texted", f.at(CallStep.ADDRESS, f.texted), f.link()),
        OrderShot("call-08-address-text-failed", f.at(CallStep.ADDRESS, f.texted), f.link(error = "RESULT_ERROR_NO_SERVICE")),
        OrderShot("call-09-pickup", f.at(CallStep.ADDRESS, pickup(f.named))),
        OrderShot("call-10-dishes-empty", f.at(CallStep.DISHES, f.addressed)),
        OrderShot("call-11-dishes", f.at(CallStep.DISHES, f.noted(5))),
        OrderShot("call-12-dishes-twelve", f.at(CallStep.DISHES, f.priced(12))),
        OrderShot("call-13-basket-up", f.at(CallStep.DISHES, f.noted(6)), on = { it.phone }, act = { onNodeWithText("Warenkorb", substring = true).performClick() }),
        OrderShot("call-14-options", f.at(CallStep.DISHES, f.priced(3)), act = { onAllNodesWithText("Dürüm Döner").onFirst().performClick() }),
        OrderShot("call-15-check", f.at(CallStep.CONFIRM, f.noted(5))),
        OrderShot("call-16-check-pickup", f.at(CallStep.CONFIRM, pickup(f.with(3)))),
        OrderShot("call-17-check-texted", f.at(CallStep.CONFIRM, f.with(3, f.texted)), f.link()),
        OrderShot("call-18-check-refused", f.at(CallStep.CONFIRM, f.with(3)).copy(error = "Der Preis hat sich geändert. Bitte den neuen Preis prüfen und erneut speichern.")),
        OrderShot("call-19-done", f.saved()),
        OrderShot("call-20-done-waiting", f.waiting(), f.link()),
        OrderShot("call-21-done-problem", f.waiting(), f.link(problem = "HB426")),
        OrderShot("call-22-menu-failed", f.at(CallStep.DISHES, f.addressed.copy(menu = null, menuError = "Keine Verbindung zum Server."))),
        OrderShot("call-23-dark", f.at(CallStep.DISHES, f.noted(5)), night = true, on = { it == TillDevice.TABLET_LANDSCAPE }),
        OrderShot("new-01-empty", f.called.copy(draft = f.called.draft.copy(fulfilment = Fulfilment.PICKUP, phone = "")), fullForm = true),
        OrderShot("new-02-delivery", f.noted(3), fullForm = true),
        OrderShot("new-03-menu", f.priced(3), fullForm = true, on = { !it.landscape }, act = { onNodeWithText("Gericht hinzufügen", substring = true).performClick() }),
    )
}

/**
 * The pictures of an order taken on the phone, every state on every screen,
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
            if (shot.fullForm) NewOrderContent(shot.state, TillFixtures.photos, NoOrder(), {}, listOf(20, 30, 45, 60), navigation)
            else CallOrderContent(shot.state, TillFixtures.photos, shot.link, NoOrder(), {}, {}, {}, {}, navigation)
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
