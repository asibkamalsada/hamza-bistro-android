package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.hamzabistro.printstation.core.Growth
import de.hamzabistro.printstation.core.GrowthCustomers
import de.hamzabistro.printstation.core.GrowthMonth
import de.hamzabistro.printstation.core.GrowthSpan
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fortschritt, Kunden and Monat für Monat, drawn on every screen: build/kasse-shots, which CI keeps. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class GrowthShotsTest(private val device: TillDevice) {
    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(OnDevice(device)).around(compose)

    private val growth =
        Growth(
            current = GrowthSpan("2026-11-01", "2026-11-28", 412, 9876.5, 23.97, 301, 87, 0.289, 1874.2, 64),
            previous = GrowthSpan("2026-10-01", "2026-10-28", 365, 8420.0, 23.07, 270, 92, 0.3407, 1950.0, 58),
            customers = GrowthCustomers(1243, 1243, 811, 233, 71),
            months =
                listOf(
                    GrowthMonth("2026-09-01", 340, 7712.3, 250, 120, 140, 1087),
                    GrowthMonth("2026-10-01", 401, 9254.1, 296, 99, 92, 1179),
                    GrowthMonth("2026-11-01", 412, 9876.5, 301, 87, 64, 1243),
                ),
        )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) =
        captureScreenRoboImage(File("build/kasse-shots", "$name--${device.name.lowercase().replace('_', '-')}.png"))

    private fun draw(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setScreen {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }

    @Test
    fun progress() {
        draw { GrowthProgress(growth) }
        shot("report-progress")
        for (text in listOf("Fortschritt", "Im Vergleich zu 01.10.2026 – 28.10.2026", "+47 · +12,9 %", "−5,2 Pkt.", "+6 · +10,3 %")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun customers() {
        draw { GrowthCustomersSection(growth) }
        shot("report-customers")
        for (text in listOf("Registriert bis 28.11.2026", "Registrierte Kunden", "28,9 %", "Schon online bestellt (65,2 %)", "davon mehrmals (30,5 %)")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun months() {
        draw { GrowthMonths(growth) }
        shot("report-months")
        for (text in listOf("Monat für Monat", "Der letzte Monat zählt bis 28.11.2026.")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun all(): List<Array<Any>> = TillDevice.entries.map { arrayOf(it) }
    }
}
