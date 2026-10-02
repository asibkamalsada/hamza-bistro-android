package de.hamzabistro.printstation.core

import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DevicesTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseDevicesBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    @Test
    fun `lists the app's devices on shift, and which of them still listen`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"id":"d1","mine":true,"label":"Tablet","alarm":"loop","started_at":"2026-10-02T09:00:00+00:00","last_seen_at":"2026-10-02T10:28:30+00:00"},""" +
                """{"id":"d2","mine":false,"label":"Fahrer 1","alarm":"off","started_at":"2026-10-02T09:00:00+00:00","last_seen_at":"2026-10-02T10:20:00+00:00"}]""",
        )

        val devices = backend.appDevices()
        val now = at("2026-10-02T10:30:00Z")
        assertEquals(listOf("Tablet", "Fahrer 1"), devices.map { it.label })
        assertTrue(devices[0].listening(now))
        assertTrue(devices[0].rings(now))
        // Ten minutes unheard: off, flat, or killed by a battery saver.
        assertFalse(devices[1].listening(now))
        assertFalse(devices[1].copy(lastSeenAt = now).rings(now))

        test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_app_devices", test.server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a database without the device list has none`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.staff_app_devices"}""")
        assertEquals(emptyList(), backend.appDevices())
    }

    @Test
    fun `takes a lost device or a dead print station off its list`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(204)
        test.reply(204)

        backend.removeAppDevice("d2")
        backend.removePrintStation("station-9")

        test.server.takeRequest()
        val device = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_app_off", device.url.encodedPath)
        assertEquals("""{"p_device":"d2"}""", device.body!!.utf8())
        val station = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/print_station_off", station.url.encodedPath)
        assertEquals("""{"p_station":"station-9"}""", station.body!!.utf8())
    }

    @Test
    fun `reads the phones with pushes, and which of them are failing`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"mine":false,"this_device":false,"label":"Handy","lang":"de","notify_new":true,"notify_reminders":false,""" +
                """"notify_preorders":true,"loud":true,"quiet_from":"22:00:00","quiet_to":"09:00:00","last_seen_at":"2026-10-01T09:00:00+00:00",""" +
                """"last_sent_at":"2026-10-01T18:00:00+00:00","last_error_at":"2026-10-01T19:00:00+00:00","last_error":"410 Gone"}]""",
        )

        val phone = backend.pushDevices().single()
        assertEquals(LocalTime.of(22, 0), phone.quietFrom)
        assertFalse(phone.notifyReminders)
        // A column from a later migration, missing here, is simply off.
        assertFalse(phone.notifyUnprinted)
        assertTrue(phone.failing)
        assertFalse(phone.copy(lastSentAt = at("2026-10-01T19:05:00Z")).failing)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_push_devices", request.url.encodedPath)
        // The app has no push subscription of its own.
        assertEquals("""{"p_endpoint":null}""", request.body!!.utf8())
    }

    @Test
    fun `lists the print stations with this device marked`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"id":"station-1","this_station":true,"label":"Android-App","registered_at":"2026-09-30T10:00:00+00:00","last_seen_at":"2026-10-02T10:29:00+00:00"}]""",
        )
        val station = backend.printStations("station-1").single()
        assertTrue(station.thisStation)
        test.server.takeRequest()
        assertEquals("""{"p_station":"station-1"}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `the newest outcome of the address check is the one that counts`() {
        val found = at("2026-10-02T10:00:00Z")
        val later = at("2026-10-02T10:05:00Z")
        val ok = AddressCheckHealth(lastFoundAt = found)
        assertEquals(AddressVerdict.Ok(found), ok.verdict())
        assertTrue(ok.verdict().ok)

        assertEquals(AddressVerdict.Failing(later, "403"), ok.copy(lastErrorAt = later, lastError = "403").verdict())
        // An error before the last address found is over.
        assertEquals(AddressVerdict.Ok(later), ok.copy(lastFoundAt = later, lastErrorAt = found).verdict())
        assertEquals(AddressVerdict.Unconfigured, ok.copy(lastUnconfiguredAt = later).verdict())
        assertEquals(AddressVerdict.Fallback(later, "timeout"), ok.copy(lastFallbackAt = later, lastFallback = "timeout").verdict())
        // The first geocoder answering again, even "not found", ends the fallback line.
        val recovered = ok.copy(lastFallbackAt = found, lastNotFoundAt = later, lastFoundAt = at("2026-10-02T09:00:00Z"))
        assertEquals(AddressVerdict.Ok(at("2026-10-02T09:00:00Z")), recovered.verdict())
        assertEquals(AddressVerdict.NoData, AddressCheckHealth().verdict())
    }

    @Test
    fun `reads the address check's health`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """{"require_check":false,"last_found_at":"2026-10-02T10:00:00+00:00","last_not_found_at":null,"last_error_at":null,""" +
                """"last_error":null,"last_busy_at":null,"last_unconfigured_at":null,"last_fallback_at":null,"last_fallback":null,""" +
                """"deliveries_7d":40,"unverified_7d":3}""",
        )
        val health = backend.addressHealth()
        assertFalse(health.requireCheck)
        assertEquals(3, health.unverified7d)
        assertEquals(40, health.deliveries7d)
    }

    @Test
    fun `tests the address check live, as staff only`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"ok":true,"outcome":"found","zone":"near","postal_code":"04177","via":"nominatim.example","ms":412}""")
        test.reply(403, """{"error":"staff only"}""")

        val probe = backend.testAddress()
        assertTrue(probe.ok)
        assertEquals("04177", probe.postalCode)
        assertEquals(412, probe.ms)
        assertFailsWith<NotAllowedException> { backend.testAddress() }

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/functions/v1/check-address", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals("""{"probe":true}""", request.body!!.utf8())
    }
}
