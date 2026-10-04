package de.hamzabistro.printstation

import androidx.test.core.app.ApplicationProvider
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashLogTest {
    private val app: PrintStationApp = ApplicationProvider.getApplicationContext()
    private var before: Thread.UncaughtExceptionHandler? = null

    @Before
    fun keep() {
        before = Thread.getDefaultUncaughtExceptionHandler()
        CrashLog.clear(app)
    }

    @After
    fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(before)
        CrashLog.clear(app)
    }

    @Test
    fun anUncaughtCrashIsKeptForTheNextStartAndHandedOn() {
        val handedOn = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn += e }
        CrashLog.install(app)

        val crash = IllegalStateException("boom")
        Thread({ throw crash }, "crasher").apply { start(); join() }

        // Android's own handler still closes the app, as before.
        assertEquals(1, handedOn.size)
        assertSame(crash, handedOn.single())
        val kept = CrashLog.pending(app)!!
        assertTrue(kept, "java.lang.IllegalStateException: boom" in kept)
        assertTrue(kept, "thread crasher" in kept)

        // "Weiter": not shown again.
        CrashLog.clear(app)
        assertNull(CrashLog.pending(app))
    }

    @Test
    fun nothingIsPendingAfterAGoodRun() {
        assertNull(CrashLog.pending(app))
    }

    @Test
    fun aFailureInTheGraphsScopeEndsOnlyItsOwnWork() = runBlocking {
        val scope = app.graph.scope
        val failed = scope.launch { throw NoClassDefFoundError("a serializer R8 took away") }
        failed.join()
        assertTrue(failed.isCancelled)
        // The scope, and what else runs in it, goes on.
        assertTrue(scope.isActive)
        val after = scope.launch { delay(1) }
        withTimeout(5.seconds) { after.join() }
        assertTrue(after.isCompleted && !after.isCancelled)
    }
}
