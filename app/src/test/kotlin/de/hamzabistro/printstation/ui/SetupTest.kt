package de.hamzabistro.printstation.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import de.hamzabistro.printstation.station.Role
import de.hamzabistro.printstation.station.StationSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The start-up screen after an install: what it asks for, when it comes up
 * again, and that it gets out of the way. Android 13, where notifications
 * are a permission and the full screen is not yet one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SetupTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = StationSettings(app)

    @Before
    fun everythingAllowed() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, *Setup.NEARBY_PERMISSIONS.toTypedArray())
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
    }

    private fun batteryOptimised() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, false)
    }

    @Test
    fun withEverythingAllowedItStaysAway() {
        check(Setup.missing(app, Role.STAFF).isEmpty())
        check(!Setup.due(app, settings))
    }

    @Test
    fun theFirstStartAsksForTheBluetoothTooAfterThatOnlyWhatTheAlarmNeeds() {
        shadowOf(app).denyPermissions(*Setup.NEARBY_PERMISSIONS.toTypedArray())
        check(Setup.missing(app, Role.STAFF) == listOf(Need.NEARBY))
        check(Setup.due(app, settings))
        // Asked once: a driver's phone without a printer is not asked again for Bluetooth.
        settings.permissionsAsked = true
        check(!Setup.due(app, settings))
        // But a tablet that lost what the alarm needs is.
        batteryOptimised()
        check(Setup.due(app, settings))
    }

    @Test
    fun aDeviceWithoutBluetoothIsNotAskedForIt() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, false)
        shadowOf(app).denyPermissions(*Setup.NEARBY_PERMISSIONS.toTypedArray())
        check(Need.NEARBY !in Setup.needs(app, Role.STAFF))
        check(!Setup.due(app, settings))
    }

    @Test
    fun itAsksAtOnceAndGoesOnWithoutWhenTold() {
        batteryOptimised()
        var done = false
        compose.setContent { AppTheme { SetupScreen(Role.STAFF, onDone = { done = true }) } }
        compose.waitForIdle()
        // Asked without a tap: Android's battery dialog was opened.
        check(shadowOf(app).nextStartedActivity?.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Background running").assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
        check(!done)
        compose.onNodeWithText("Continue anyway").performScrollTo().performClick()
        check(done)
    }

    @Test
    fun withEverythingAllowedItGoesOnByItself() {
        var done = false
        compose.setContent { AppTheme { SetupScreen(Role.STAFF, onDone = { done = true }) } }
        compose.waitForIdle()
        check(done)
    }
}
