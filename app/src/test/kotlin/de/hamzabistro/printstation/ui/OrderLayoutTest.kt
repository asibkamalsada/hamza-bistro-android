package de.hamzabistro.printstation.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.hamzabistro.printstation.core.AddressRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What Telefon-Bestellung owes whoever takes the call, on the shop's tablet
 * and a phone: the button that goes on always there, the keyboard up or not,
 * and as many dishes and lines in sight as in the Kasse. The pictures of it
 * all are [OrderShotsTest]'s.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OrderLayoutTest(private val device: TillDevice) {
    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(OnDevice(device)).around(compose)

    private val f = OrderFixtures

    private fun show(state: NewOrderState, link: AddressRequest? = null, keyboard: Boolean = false) =
        compose.setScreen(if (keyboard) device.keyboard else null) {
            CallOrderContent(state, TillFixtures.photos, link, NoOrder(), {}, {}, {}, {}) { MenuButton(0, onClick = {}) }
        }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> = compose.onAllNodes(matcher).fetchSemanticsNodes()

    /** All of it in sight: nothing cut off, nothing under the system's bars or the keyboard. */
    private fun SemanticsNode.whole(keyboard: Boolean = false): Boolean {
        val density = layoutInfo.density.density
        val screen = generateSequence(this) { it.parent }.last().size
        val below = if (keyboard) device.keyboard.value else 48f
        val seen = boundsInRoot
        return seen.width >= size.width - 1 && seen.height >= size.height - 1 && seen.top >= 32 * density - 1 && seen.bottom <= screen.height - below * density + 1
    }

    private fun assertWhole(matcher: SemanticsMatcher, what: String, keyboard: Boolean = false) {
        val found = nodes(matcher)
        assertEquals("$what on $device", 1, found.size)
        assertTrue("$what is cut off on $device", found.single().whole(keyboard))
    }

    @Test
    fun theNameIsTypedWithTheWayOnInSight() {
        show(f.named, keyboard = true)
        assertWhole(hasText("Name") and hasSetTextAction(), "name", keyboard = true)
        assertWhole(hasText("Weiter", substring = true) and hasClickAction(), "Weiter", keyboard = true)
    }

    @Test
    fun theStreetsToPickShowWhileTheStreetIsTyped() {
        show(f.at(CallStep.ADDRESS, f.typing), keyboard = true)
        if (device.phone) return
        assertWhole(hasText("Straße und Hausnummer") and hasSetTextAction(), "street", keyboard = true)
        assertWhole(hasText("Karl-Heine-Straße 9") and hasClickAction(), "the street to pick", keyboard = true)
    }

    @Test
    fun theWayOnIsThereWithTheKeyboardUp() {
        show(f.at(CallStep.ADDRESS, f.addressed), keyboard = true)
        assertWhole(hasText("Weiter", substring = true) and hasClickAction(), "Weiter", keyboard = true)
    }

    @Test
    fun theDishesAndTheBasketShowAsInTheKasse() {
        show(f.at(CallStep.DISHES, f.priced(5)))
        if (device.phone) {
            compose.onNodeWithText("Warenkorb", substring = true).performClick()
            compose.waitForIdle()
        }
        assertWhole(hasTestTag(BASKET_GO_TAG), "Weiter")
        val lines = nodes(hasTestTag(BASKET_LINE_TAG)).filter { it.whole() }
        assertEquals("lines on $device", 5, lines.size)
        if (device.phone) return
        val tiles = nodes(hasTestTag(DISH_TILE_TAG)).filter { it.whole() }
        assertTrue("${tiles.size} dishes on $device", tiles.size >= 8)
    }

    @Test
    fun savingIsInReachWhateverTheBasket() {
        show(f.at(CallStep.CONFIRM, f.priced(12)))
        assertWhole(hasTestTag(BASKET_GO_TAG), "Speichern")
        assertWhole(hasText("Fertig in ca. 35 Min."), "the minutes")
    }

    @Test
    fun nothingToTapIsSmallerThanAFinger() {
        val density = compose.density.density
        show(f.at(CallStep.DISHES, f.noted(5)))
        val small =
            nodes(hasClickAction()).filter { it.config.getOrNull(SemanticsActions.OnClick) != null && it.whole() }.filter {
                val touch = it.touchBoundsInRoot
                touch.width < 48 * density - 1 || touch.height < 48 * density - 1
            }
        assertEquals("on $device", emptyList<String>(), small.map { it.config.getOrNull(SemanticsProperties.Text).toString() + it.touchBoundsInRoot })
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun all(): List<Array<Any>> = TillDevice.entries.map { arrayOf(it) }
    }
}
