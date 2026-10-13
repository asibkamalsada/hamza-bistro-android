package de.hamzabistro.printstation.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.OrderMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Telefon-Bestellung on a phone's screen: drawn as it is, not zoomed, and
 * the dishes there to tap with the basket and the buttons under them. At
 * 1.3 times, the basket's line was squeezed between the buttons to a letter
 * a line, and the bar under the dishes grew over all of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class CallOrderPhoneTest {
    @get:Rule val compose = createComposeRule()

    private fun dish(id: Long, name: String, price: Double) = MenuDish(id, "Speisen", name, "", price, 10, id.toInt(), "", true, emptyList())

    private val menu = OrderMenu(listOf(dish(1, "Döner", 7.5), dish(2, "Dürüm", 8.0), dish(3, "Falafel", 6.5), dish(4, "Lahmacun", 5.0)), emptyList())

    @Test
    fun aPhoneIsNotZoomedATabletIs() {
        assertEquals(1f, zoomFor(360.dp), 0.001f)
        assertEquals(1f, zoomFor(440.dp), 0.001f)
        assertEquals(1.15f, zoomFor(540.dp), 0.001f)
        assertEquals(1.3f, zoomFor(640.dp), 0.001f)
        assertEquals(1.3f, zoomFor(800.dp), 0.001f)
    }

    @Test
    fun theDishesTheBasketAndTheButtonsAllShowOnAPhone() {
        compose.setContent {
            AppTheme {
                Zoomed {
                    DishesPage(
                        menu = menu,
                        menuError = null,
                        lines = listOf(BasketLine(menu.dishes[0], 3)),
                        photos = emptyMap(),
                        onPhoto = {},
                        onRetry = {},
                        onBack = {},
                        onNext = {},
                    ) { _, _ -> }
                }
            }
        }
        compose.onNodeWithText("Which dishes?").assertIsDisplayed()
        // Two to a row: the first row is on screen.
        compose.onNodeWithText("Döner").assertIsDisplayed()
        compose.onNodeWithText("Dürüm").assertIsDisplayed()
        // The euro sign comes after a no-break space: matched up to it.
        compose.onNodeWithText("🛒 3 dishes · 22,50", substring = true).assertIsDisplayed()
        compose.onNodeWithText("← Back").assertIsDisplayed()
        compose.onNodeWithText("Next ➜").assertIsDisplayed()
    }
}
