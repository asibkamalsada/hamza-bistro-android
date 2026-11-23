package de.hamzabistro.printstation.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.hamzabistro.printstation.core.CounterReceipt
import de.hamzabistro.printstation.core.TillState
import de.hamzabistro.printstation.core.TillStep
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
 * What the Kasse owes whoever stands at it, on the shop's tablet and a phone,
 * at the usual font size and at 1.3 times: the basket and the dishes in sight
 * together, and the button that goes on always there. The pictures of it all
 * are [TillShotsTest]'s.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TillLayoutTest(private val device: TillDevice) {
    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(OnDevice(device)).around(compose)

    private val f = TillFixtures

    private fun show(state: TillState) = compose.setScreen { TillContent(state, f.view, f.photos, NoTill(), onBack = {}) }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> = compose.onAllNodes(matcher).fetchSemanticsNodes()

    /** All of it in sight: nothing cut off, nothing under the system's bars. */
    private fun SemanticsNode.whole(): Boolean {
        val density = layoutInfo.density.density
        val screen = generateSequence(this) { it.parent }.last().size
        val seen = boundsInRoot
        return seen.width >= size.width - 1 && seen.height >= size.height - 1 && seen.top >= 32 * density - 1 && seen.bottom <= screen.height - 48 * density + 1
    }

    private fun assertWhole(matcher: SemanticsMatcher, what: String) {
        val found = nodes(matcher)
        assertEquals("$what on $device", 1, found.size)
        assertTrue("$what is cut off on $device", found.single().whole())
    }

    @Test
    fun fiveLinesWithTheirQuantitiesAndEightDishesShowAtOnce() {
        if (device.phone) return
        show(f.basket(5))
        assertWhole(hasTestTag(TILL_RING_UP_TAG), "Kassieren")
        val lines = nodes(hasTestTag(TILL_LINE_TAG)).filter { it.whole() }
        val more = nodes(hasContentDescription("eins mehr", substring = true)).filter { it.whole() }
        val less = nodes(hasContentDescription("eins weniger", substring = true)).filter { it.whole() }
        val tiles = nodes(hasTestTag(TILL_TILE_TAG)).filter { it.whole() }
        assertEquals("lines on $device", 5, lines.size)
        assertEquals("plus on $device", 5, more.size)
        assertEquals("minus on $device", 5, less.size)
        assertTrue("${tiles.size} dishes on $device", tiles.size >= 8)
    }

    @Test
    fun ringingUpIsInReachWhateverTheBasket() {
        show(f.basket(12).copy(open = f.openSales))
        if (device.phone) {
            compose.onNodeWithText("Zur Kasse", substring = true).performClick()
            compose.waitForIdle()
        }
        assertWhole(hasTestTag(TILL_RING_UP_TAG), "Kassieren")
        assertWhole(hasText("Mitnehmen"), "Mitnehmen")
        // And that there is more of it than shows is said.
        assertWhole(hasText("weitere", substring = true), "what is further down")
    }

    @Test
    fun cashIsCountedWithoutScrollingForTheButton() {
        show(f.at(TillStep.CASH).copy(given = "50"))
        assertWhole(hasText("Bar bezahlt", substring = true) and hasClickAction(), "Bar bezahlt")
        assertWhole(hasText("22,50", substring = true), "Rückgeld")
        // The keys, all of them, where there is the room of a tablet.
        if (!device.phone) for (key in listOf("1", "5", "9", "0", ",")) assertWhole(hasText(key) and hasClickAction(), key)
    }

    @Test
    fun bothWaysToPayAndBothWaysOutShow() {
        show(f.at(TillStep.PAY, f.sale(12)))
        for (text in listOf("Bar", "Karte", "Verkauf abbrechen", "Später abschließen")) assertWhole(hasText(text) and hasClickAction(), text)
    }

    @Test
    fun theCardsAmountAndAnswersShow() {
        show(f.at(TillStep.CARD))
        assertWhole(hasText("27,50", substring = true), "amount")
        for (text in listOf("Abgelehnt", "Karte bezahlt")) assertWhole(hasText(text, substring = true) and hasClickAction(), text)
    }

    @Test
    fun theNextSaleIsOneTapAway() {
        show(f.done(CounterReceipt("0123456789abcdef0123456789abcdef", "signed")))
        assertWhole(hasText("Nächster Verkauf", substring = true) and hasClickAction(), "next")
        if (!device.phone) assertWhole(hasContentDescription("QR-Code des Belegs"), "QR")
    }

    @Test
    fun nothingToTapIsSmallerThanAFinger() {
        show(f.basket(5).copy(open = f.openSales))
        val density = compose.density.density
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
