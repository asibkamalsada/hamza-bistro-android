package de.hamzabistro.printstation.ui

import androidx.compose.foundation.layout.Box
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
import de.hamzabistro.printstation.core.ReportCameFrom
import de.hamzabistro.printstation.core.ReportText
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Where the web orders came from, drawn on every screen: build/kasse-shots, which CI keeps. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ReportShotsTest(private val device: TillDevice) {
    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(OnDevice(device)).around(compose)

    private val rows =
        listOf(
            ReportCameFrom("instagram-ad", 34, 31, 22, 701.4, 22.6),
            ReportCameFrom("menu-or-typed", 28, 26, 9, 569.3, 21.9),
            ReportCameFrom("direct", 21, 20, 4, 431.2, 21.6),
            ReportCameFrom("facebook-ad", 12, 11, 7, 243.1, 22.1),
            ReportCameFrom("instagram-ad-pizza", 5, 5, 5, 98.5, 19.7),
            ReportCameFrom("nebenan.de", 1, 0, 0, 0.0, null),
            ReportCameFrom(null, 3, 3, 1, 1060.0, 20.0),
        )

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun cameFrom() {
        compose.setScreen {
            Box(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 56.dp)) {
                CameFrom(rows)
            }
        }
        captureScreenRoboImage(File("build/kasse-shots", "report-came-from--${device.name.lowercase().replace('_', '-')}.png"))
        for (name in listOf("Instagram-Anzeige", "Gedruckte Karte oder getippt", "Facebook-Anzeige", "instagram-ad-pizza", "nebenan.de", "Unbekannt", "31 geliefert, 22 neu", ReportText.euro(1060.0))) {
            compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun all(): List<Array<Any>> = TillDevice.entries.map { arrayOf(it) }
    }
}
