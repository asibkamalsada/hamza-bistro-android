package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.core.CounterReceipt
import de.hamzabistro.printstation.core.TillState
import de.hamzabistro.printstation.core.TillStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun show(state: TillState, view: TillView = f.view) = compose.setScreen { TillContent(state, view, f.photos, NoTill(), onBack = {}) }

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

    /** The names of the dishes in sight, in reading order. */
    private fun tiles(): List<String> =
        nodes(hasTestTag(TILL_TILE_TAG)).filter { it.whole() }.sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left })).map {
            it.config[SemanticsProperties.Text].first().text
        }

    @Test
    fun theBestsellersAreTheFirstDishesAndTheCategoriesStayAsTheCardHasThem() {
        show(f.empty, f.ranked)
        val first = tiles()
        assertTrue("${first.size} dishes on $device", first.size >= if (device.phone) 4 else 6)
        assertEquals("on $device", f.bestsellers.take(first.size), first.take(f.bestsellers.size))

        // In its category a dish is where it always was.
        compose.onAllNodes(hasText("Döner") and hasClickAction() and !hasTestTag(TILL_TILE_TAG)).onFirst().performClick()
        compose.waitForIdle()
        val doener = f.menu.dishes.filter { it.category == "Döner" }.map { it.name }
        val inCategory = tiles()
        assertEquals("on $device", doener.take(inCategory.size), inCategory)

        // And back on all of them, the bestsellers are in sight again.
        compose.onNode(hasText("Alle") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals("on $device", first, tiles())
    }

    @Test
    fun aLineSaysWhereTheBestsellersEnd() {
        show(f.empty, f.ranked)
        compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasTestTag(TILL_TILE_TAG))).performScrollToNode(hasTestTag(TILL_BESTSELLERS_END_TAG))
        assertEquals("on $device", 1, nodes(hasTestTag(TILL_BESTSELLERS_END_TAG)).size)
    }

    @Test
    fun withoutBestsellersTheDishesAreInTheCardsOrder() {
        show(f.empty)
        val first = tiles()
        assertEquals("on $device", f.menu.dishes.take(first.size).map { it.name }, first)
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
    fun theWordsOnTheButtonsAreNotCutShort() {
        show(f.basket(12))
        if (device.phone) {
            compose.onNodeWithText("Zur Kasse", substring = true).performClick()
            compose.waitForIdle()
        }
        for (text in listOf("Mitnehmen", "Hier essen", "Kassieren")) assertFalse("$text is cut short on $device", cutShort(text))
    }

    /** The check above sees a word that is. */
    @Test
    fun aWordCutShortIsSeen() {
        compose.setContent { Text("Mitnehmen", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(40.dp)) }
        assertTrue(cutShort("Mitnehmen"))
    }

    /** Whether [text], one line of it, needs more room than it has: an ellipsis is not said in its layout. */
    private fun cutShort(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
        val input = layouts.single().layoutInput
        val needs = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection).measure(text, input.style, maxLines = 1, softWrap = false)
        return needs.size.width > node.size.width + 1
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
