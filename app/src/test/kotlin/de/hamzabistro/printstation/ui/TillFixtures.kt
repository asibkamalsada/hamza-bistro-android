package de.hamzabistro.printstation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.CounterLine
import de.hamzabistro.printstation.core.CounterPrice
import de.hamzabistro.printstation.core.CounterReceipt
import de.hamzabistro.printstation.core.CounterSale
import de.hamzabistro.printstation.core.CounterStatus
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.MenuOption
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.Selection
import de.hamzabistro.printstation.core.TillState
import de.hamzabistro.printstation.core.TillStep
import java.time.Instant
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment

/**
 * A screen the Kasse is drawn for, in German.
 *
 * The shop's tablet is an Honor Pad X8a: 1920×1200, and 280 dpi as it is
 * set (worked out from a photo of the Kasse on it, where a column of 468 dp
 * took 43 % of the width), so 1097×686 dp.
 */
enum class TillDevice(val qualifiers: String, val fontScale: Float) {
    TABLET_LANDSCAPE("de-rDE-w1097dp-h686dp-large-long-land-notnight-280dpi", 1f),
    TABLET_LANDSCAPE_BIG("de-rDE-w1097dp-h686dp-large-long-land-notnight-280dpi", 1.3f),
    TABLET_PORTRAIT("de-rDE-w686dp-h1097dp-large-long-port-notnight-280dpi", 1f),
    TABLET_PORTRAIT_BIG("de-rDE-w686dp-h1097dp-large-long-port-notnight-280dpi", 1.3f),
    PHONE("de-rDE-w360dp-h800dp-normal-long-port-notnight-xhdpi", 1f),
    PHONE_BIG("de-rDE-w360dp-h800dp-normal-long-port-notnight-xhdpi", 1.3f);

    val phone: Boolean
        get() = this == PHONE || this == PHONE_BIG

    val landscape: Boolean
        get() = this == TABLET_LANDSCAPE || this == TABLET_LANDSCAPE_BIG
}

/** Makes the device before the test's activity starts: goes outside the compose rule. */
class OnDevice(private val device: TillDevice, private val night: Boolean = false) : ExternalResource() {
    override fun before() {
        RuntimeEnvironment.setQualifiers(if (night) device.qualifiers.replace("notnight", "night") else device.qualifiers)
        RuntimeEnvironment.setFontScale(device.fontScale)
    }
}

private val STATUS_BAR = 32.dp
private val NAVIGATION_BAR = 48.dp

/** The content as MainActivity frames it, with the bars the tablet has: nothing of it may lie under them. */
internal fun ComposeContentTestRule.setScreen(content: @Composable () -> Unit) {
    var dispatch: (() -> Unit)? = null
    setContent {
        val view = LocalView.current
        dispatch = {
            val density = view.resources.displayMetrics.density
            val insets =
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (STATUS_BAR.value * density).toInt(), 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (NAVIGATION_BAR.value * density).toInt()))
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        AppTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
                content()
                val bar = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(STATUS_BAR).background(bar))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(NAVIGATION_BAR).background(bar))
            }
        }
    }
    waitForIdle()
    runOnUiThread { dispatch?.invoke() }
    waitForIdle()
}

/** Takes every tap and does nothing with it. */
internal open class NoTill(override val canPrint: Boolean = true) : TillActions {
    override fun loadPhoto(url: String) = Unit

    override fun loadMenu() = Unit

    override fun add(line: BasketLine) = Unit

    override fun setQty(index: Int, qty: Int) = Unit

    override fun setDineIn(dineIn: Boolean) = Unit

    override fun setTraining(training: Boolean) = Unit

    override fun clear() = Unit

    override fun ringUp() = Unit

    override fun resume(sale: CounterSale) = Unit

    override fun chooseCash() = Unit

    override fun chooseCard() = Unit

    override fun setGiven(text: String) = Unit

    override fun cashPaid() = Unit

    override fun cardPaid() = Unit

    override fun cardDeclined() = Unit

    override fun back() = Unit

    override fun abort() = Unit

    override fun park() = Unit

    override fun next() = Unit

    override fun dismissProblem() = Unit

    override fun printReceipt() = Unit
}

/** A menu like the shop's: its categories, long names, choices, a sold-out dish, some photos. */
internal object TillFixtures {
    private fun option(id: Long, name: String, price: Double = 0.0, available: Boolean = true) =
        MenuOption(id, name, price, available, emptyList())

    private val meat =
        OptionGroup(
            1,
            "Dein Fleisch",
            listOf(option(11, "Kalb"), option(12, "Hähnchen"), option(13, "Gemischt", 0.5)),
            listOf("Döner"),
            Selection.SINGLE,
        )
    private val sauces =
        OptionGroup(
            2,
            "Soßen",
            listOf(
                option(21, "Kräutersoße"),
                option(22, "Knoblauchsoße"),
                option(23, "Scharfe Soße"),
                option(24, "Mango-Curry-Soße"),
                option(25, "Ohne Soße"),
            ),
            listOf("Döner"),
        )
    private val salad =
        OptionGroup(
            3,
            "Ohne",
            listOf(
                option(31, "ohne Zwiebeln"),
                option(32, "ohne Tomaten"),
                option(33, "ohne Gurken"),
                option(34, "ohne Rotkraut"),
                option(35, "ohne Eisbergsalat"),
            ),
            listOf("Döner"),
        )
    private val extras =
        OptionGroup(
            4,
            "Extras",
            listOf(
                option(41, "Schafskäse", 1.0),
                option(42, "Extra Fleisch", 2.5),
                option(43, "Überbacken mit Käse", 1.5),
                option(44, "Jalapeños", 0.5),
                option(45, "Gegrilltes Gemüse", 1.0, available = false),
            ),
            listOf("Döner"),
        )
    private val side =
        OptionGroup(
            5,
            "Beilage",
            listOf(option(51, "mit Nudeln"), option(52, "mit Reis"), option(53, "mit Pommes frites"), option(54, "mit Salat")),
            listOf("Koftah"),
            Selection.SINGLE,
        )

    private var next = 0L

    private fun dish(
        category: String,
        name: String,
        price: Double,
        photo: Boolean = false,
        groups: List<Long> = emptyList(),
        available: Boolean = true,
    ): MenuDish {
        next += 1
        return MenuDish(
            next,
            category,
            name,
            "",
            price,
            10,
            next.toInt(),
            if (photo) "photo:$next" else "",
            available,
            emptyList(),
            groupIds = groups,
            deposit = if (category == "Getränke") 0.25 else 0.0,
        )
    }

    private val all = listOf(1L, 2L, 3L, 4L)

    val menu =
        OrderMenu(
            listOf(
                dish("Döner", "Döner", 6.0, photo = true, groups = all),
                dish("Döner", "Big Döner", 7.0, groups = all),
                dish("Döner", "Dürüm Döner", 7.5, photo = true, groups = all),
                dish("Döner", "Dönerteller mit Pommes frites", 11.0, photo = true, groups = all),
                dish("Döner", "Döner-Box", 6.5, groups = listOf(1, 2)),
                dish("Döner", "Lahmacun mit Dönerfleisch und Salat", 8.0, groups = all),
                dish("Schawarma", "Hähnchen-Schawarma im Saj-Brot", 7.5, photo = true, groups = all),
                dish("Schawarma", "Schawarma-Teller", 12.0, photo = true, groups = listOf(5)),
                dish("Schawarma", "Arabisches Schawarma-Menü", 13.5),
                dish("Grillgerichte", "Koftah", 9.0, photo = true, groups = listOf(5)),
                dish("Grillgerichte", "Schisch Tawuk", 10.5, photo = true, groups = listOf(5)),
                dish("Grillgerichte", "Gemischter Grillteller für zwei Personen", 24.9, photo = true, groups = listOf(5)),
                dish("Grillgerichte", "Lammkoteletts", 16.0, groups = listOf(5), available = false),
                dish("Vegetarisch", "Falafel im Brot", 5.5, photo = true, groups = listOf(2, 3)),
                dish("Vegetarisch", "Falafelteller", 9.0, groups = listOf(5)),
                dish("Vegetarisch", "Halloumi-Wrap", 7.0, photo = true, groups = listOf(2, 3)),
                dish("Vegetarisch", "Makali (frittiertes Gemüse)", 8.5),
                dish("Burger", "Cheeseburger", 6.5, photo = true, groups = listOf(4)),
                dish("Burger", "Hähnchenburger", 6.5, groups = listOf(4)),
                dish("Burger", "Doppel-Cheeseburger mit Pommes", 10.9, photo = true, groups = listOf(4)),
                dish("Nudelgerichte", "Nudeln mit Hähnchen in Sahnesoße", 9.5, photo = true),
                dish("Nudelgerichte", "Nudeln Bolognese überbacken", 9.0),
                dish("Salate", "Fattusch", 6.0, photo = true),
                dish("Salate", "Tabouleh", 6.0),
                dish("Salate", "Hirtensalat mit Schafskäse", 7.5),
                dish("Fingerfood", "Pommes frites", 3.5, photo = true),
                dish("Fingerfood", "Chicken Nuggets (9 Stück)", 6.5),
                dish("Fingerfood", "Vegane Sambosa", 4.0),
                dish("Getränke", "Cola 0,33 l", 2.5),
                dish("Getränke", "Cola Zero 0,33 l", 2.5),
                dish("Getränke", "Ayran", 2.0),
                dish("Getränke", "Mineralwasser 0,5 l", 2.2),
                dish("Getränke", "Schwarzer Tee", 1.5),
                dish("Desserts", "Baklava (3 Stück)", 4.5, photo = true),
                dish("Desserts", "Kunafa", 5.5),
            ),
            listOf(meat, sauces, salad, extras, side),
        )

    private fun line(name: String, qty: Int, vararg options: MenuOption) =
        BasketLine(menu.dishes.first { it.name == name }, qty, options.toList())

    /** A basket for a big order; its first lines are smaller ones'. */
    val twelve =
        listOf(
            line("Döner", 2, meat.options[0], sauces.options[0], sauces.options[2], salad.options[0]),
            line("Koftah", 1, side.options[0]),
            line("Hähnchen-Schawarma im Saj-Brot", 1, meat.options[1], sauces.options[1]),
            line("Pommes frites", 3),
            line("Cola 0,33 l", 4),
            line("Gemischter Grillteller für zwei Personen", 1, side.options[1]),
            line(
                "Dürüm Döner", 1,
                meat.options[2], sauces.options[3], salad.options[0], salad.options[3], extras.options[0], extras.options[2],
            ),
            line("Falafel im Brot", 2),
            line("Ayran", 2),
            line("Doppel-Cheeseburger mit Pommes", 1, extras.options[3]),
            line("Baklava (3 Stück)", 1),
            line("Schwarzer Tee", 12),
        )

    val photos: Map<String, ImageBitmap?> by lazy { menu.dishes.filter { it.imageUrl.isNotEmpty() }.associate { it.imageUrl to food(it.id) } }

    /** Something like food on a plate, so a tile has a photo's weight: the shop's own photos stay out of the repository. */
    private fun food(seed: Long): ImageBitmap {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.HSVToColor(floatArrayOf(25f + seed * 3 % 20, 0.35f, 0.85f)))
        paint.color = Color.rgb(245, 243, 238)
        canvas.drawOval(20f, 25f, 300f, 225f, paint)
        for (i in 0 until 14) {
            val hue = listOf(12f, 24f, 95f, 45f, 355f)[(i + seed.toInt()) % 5]
            paint.color = Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.45f + (i * 7 % 5) * 0.1f))
            val x = 70f + (i * 53 + seed * 17) % 170
            val y = 60f + (i * 37 + seed * 29) % 110
            canvas.drawOval(x, y, x + 52, y + 34, paint)
        }
        return bitmap.asImageBitmap()
    }

    val view = TillView(menu = menu)

    /** The till on, nothing in the basket. */
    val empty = TillState(status = CounterStatus(enabled = true), available = true)

    val off = empty.copy(status = CounterStatus(enabled = false), training = true)

    /** [count] lines, priced by the database with the deal of the day off. */
    fun basket(count: Int, deal: Double = 1.0, of: TillState = empty): TillState {
        val lines = twelve.take(count)
        val total = lines.sumOf { it.unitPrice * it.qty } - deal
        return of.copy(lines = lines, price = CounterPrice(total, deal), pricedLines = lines)
    }

    fun sale(count: Int = 3, number: Long = 321): CounterSale {
        val lines = twelve.take(count)
        return CounterSale(
            "s-$number",
            number,
            "open",
            lines = lines.map { CounterLine(it.dish.id, it.dish.name, it.options.map { o -> o.name }, it.unitPrice, it.dish.deposit, it.qty) },
            total = lines.sumOf { it.unitPrice * it.qty } - 1.0,
            openedAt = Instant.parse("2026-11-23T15:24:00Z"),
            openedBy = "koch",
            openedOn = "Tablet Theke",
        )
    }

    fun at(step: TillStep, sale: CounterSale = sale(), of: TillState = empty): TillState = of.copy(step = step, sale = sale)

    /** A sale paid, with the receipt as it stands; null: none. */
    fun done(receipt: CounterReceipt?, method: String = "cash", given: String = "50"): TillState {
        val sale = sale().copy(stateName = "paid", paymentMethod = method, orderId = "o-1", receipt = receipt)
        return at(TillStep.DONE, sale).copy(given = if (method == "cash") given else "")
    }

    /** Left open: yesterday's, one with its card asked, and one parked just now. */
    val openSales =
        listOf(
            sale(5, 298).copy(openedAt = Instant.parse("2026-11-22T20:51:00Z"), stale = true, openedOn = "Handy Lieferung", openedBy = "fahrer"),
            sale(2, 319).copy(pendingMethod = "card"),
            sale(1, 320),
        )
}
