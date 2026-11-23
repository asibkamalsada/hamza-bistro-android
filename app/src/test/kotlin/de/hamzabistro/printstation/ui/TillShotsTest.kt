package de.hamzabistro.printstation.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.hamzabistro.printstation.core.CounterError
import de.hamzabistro.printstation.core.CounterReceipt
import de.hamzabistro.printstation.core.TillProblem
import de.hamzabistro.printstation.core.TillState
import de.hamzabistro.printstation.core.TillStep
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One picture of the Kasse: what it shows, and what is tapped before it is taken. */
private class TillShot(
    val name: String,
    val state: TillState,
    val view: TillView = TillFixtures.view,
    val canPrint: Boolean = true,
    val night: Boolean = false,
    val on: (TillDevice) -> Boolean = { true },
    val act: ComposeContentTestRule.(TillDevice) -> Unit = {},
)

private val shots: List<TillShot> by lazy {
    val f = TillFixtures
    fun receipt(state: String, outage: Boolean = false, training: Boolean = false, env: String? = "PROD") =
        CounterReceipt("0123456789abcdef0123456789abcdef", state, outage, training, env)
    fun training(state: TillState) = state.copy(sale = state.sale?.copy(training = true))
    val notPrinted = "Der Beleg wurde nicht gedruckt: Drucker nicht gefunden. Drucker an, Papier drin?"
    listOf(
        TillShot("01-empty", f.empty),
        TillShot("02-one-line", f.basket(1, deal = 0.0)),
        TillShot("03-five-lines", f.basket(5)),
        TillShot("04-twelve-lines", f.basket(12)),
        TillShot("05-dine-in", f.basket(6).copy(dineIn = true)),
        TillShot("06-basket-up", f.basket(6), on = { it.phone }, act = { onNodeWithText("Zur Kasse", substring = true).performClick() }),
        TillShot("07-category", f.basket(3), act = { onAllNodesWithText("Döner").onFirst().performClick() }),
        TillShot("08-options", f.basket(3), act = { onAllNodesWithText("Big Döner").onFirst().performClick() }),
        TillShot("09-pay", f.at(TillStep.PAY)),
        TillShot("10-pay-twelve", f.at(TillStep.PAY, f.sale(12).copy(dineIn = true))),
        TillShot("11-card-declined", f.at(TillStep.PAY).copy(declined = true)),
        TillShot("12-cash", f.at(TillStep.CASH)),
        TillShot("13-cash-change", f.at(TillStep.CASH).copy(given = "50")),
        TillShot("14-cash-not-enough", f.at(TillStep.CASH).copy(given = "12,5")),
        TillShot("15-card", f.at(TillStep.CARD)),
        TillShot("16-done-signed", f.done(receipt("signed"))),
        TillShot("17-done-waiting", f.done(receipt("pending"), method = "card")),
        TillShot("18-done-outage", f.done(receipt("pending", outage = true))),
        TillShot("19-done-late-test-printed", f.done(receipt("signed", outage = true, env = "TEST")), f.view.copy(printed = true)),
        TillShot("20-done-training", training(f.done(receipt("unsigned", training = true)))),
        TillShot("21-done-no-receipt-no-printer", f.done(null, method = "card"), canPrint = false),
        TillShot("22-done-print-failed", f.done(receipt("signed")), f.view.copy(printError = notPrinted)),
        TillShot("23-abort", f.at(TillStep.PAY), act = { onNodeWithText("Verkauf abbrechen").performClick() }),
        TillShot("24-open-sales", f.basket(5).copy(open = f.openSales)),
        TillShot("24-open-sales-up", f.basket(5).copy(open = f.openSales), act = { onNodeWithText("Ansehen", substring = true).performClick() }),
        TillShot("39-clear", f.basket(5), act = {
            if (it.phone) onNodeWithText("Zur Kasse", substring = true).performClick()
            onNodeWithContentDescription("Alles löschen").performClick()
        }),
        TillShot("40-options-chosen", f.basket(3), act = {
            onAllNodesWithText("Big Döner").onFirst().performClick()
            for (option in listOf("Kräutersoße", "Scharfe Soße")) onNodeWithText(option).performClick()
        }),
        TillShot("41-offline-basket-up", f.basket(6).copy(problem = TillProblem.Offline(false)), on = { it.phone }, act = {
            onNodeWithText("Zur Kasse", substring = true).performClick()
        }),
        TillShot("42-off-empty", f.off),
        TillShot("43-done-stopped-waiting", f.done(receipt("pending")).copy(stoppedWaiting = true)),
        TillShot("44-done-exact", f.done(receipt("signed"), given = "")),
        TillShot("25-open-one", f.empty.copy(open = f.openSales.takeLast(1))),
        TillShot("26-offline", f.basket(5).copy(problem = TillProblem.Offline(false))),
        TillShot("27-offline-unsure-cash", f.at(TillStep.CASH).copy(given = "50", problem = TillProblem.Offline(true))),
        TillShot("28-refused-stale", f.basket(5).copy(open = f.openSales, problem = TillProblem.Refused(CounterError.STALE))),
        TillShot("29-refused-price-busy", f.basket(5).copy(problem = TillProblem.Refused(CounterError.TOTAL_CHANGED), busy = true)),
        TillShot("30-training", f.basket(5).copy(training = true)),
        TillShot("31-off", f.basket(5, of = f.off)),
        TillShot("32-off-pay", training(f.at(TillStep.PAY, of = f.off))),
        TillShot("33-menu-loading", f.empty, TillView()),
        TillShot("34-menu-failed", f.empty, TillView(menuError = "Keine Verbindung zum Server.")),
        TillShot("35-unavailable", f.empty.copy(available = false, status = null)),
        TillShot("36-offline-pay", f.at(TillStep.PAY).copy(problem = TillProblem.Offline(true))),
        TillShot("37-dark-five-lines", f.basket(5), night = true, on = { it == TillDevice.TABLET_LANDSCAPE }),
        TillShot("38-dark-cash", f.at(TillStep.CASH).copy(given = "50"), night = true, on = { it == TillDevice.TABLET_LANDSCAPE }),
    )
}

/**
 * The Kasse's pictures, every state on every screen, for somebody to look
 * at: build/kasse-shots, which CI keeps as an artifact. They compare with
 * nothing; what must hold is in [TillScreenTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Of the versions the app runs on, the one whose stand-in colours come closest to the tablet's own.
@Config(sdk = [33])
class TillShotsTest(private val device: TillDevice, private val name: String) {
    private val shot = shots.first { it.name == name }
    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(OnDevice(device, shot.night)).around(compose)

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun draw() {
        compose.setScreen { TillContent(shot.state, shot.view, TillFixtures.photos, NoTill(shot.canPrint), onBack = {}) }
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
