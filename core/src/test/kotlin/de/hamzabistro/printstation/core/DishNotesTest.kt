package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A note per dish (hamza-bistro-web#84), as orders.items carries it. */
class DishNotesTest {
    private fun read(items: String): List<OrderItem> =
        json.decodeFromString(
            StaffOrder.serializer(),
            """{"id":"o-1","order_number":57,"created_at":"2026-10-04T12:00:00+00:00","status":"new","items":$items}""",
        ).items

    @Test
    fun `reads a dish's note`() {
        val line = read("""[{"id":2,"name":"Döner","options":["scharf"],"price":7.5,"deposit":0,"qty":1,"note":"ohne Zwiebeln"}]""").single()
        assertEquals("ohne Zwiebeln", line.note)
        assertEquals("ohne Zwiebeln", line.dishNote)
        assertEquals(listOf("scharf"), line.options)
    }

    @Test
    fun `an older order, or one past retention, has none`() {
        val line = read("""[{"id":2,"name":"Döner","price":7.5,"qty":1}]""").single()
        assertNull(line.note)
        assertNull(line.dishNote)
    }

    @Test
    fun `a blank note is none`() {
        assertNull(read("""[{"id":2,"name":"Döner","qty":1,"note":"   "}]""").single().dishNote)
        assertNull(read("""[{"id":2,"name":"Döner","qty":1,"note":""}]""").single().dishNote)
        assertNull(read("""[{"id":2,"name":"Döner","qty":1,"note":null}]""").single().dishNote)
    }

    @Test
    fun `two of the same dish with different notes stay two lines`() {
        val lines =
            read(
                """[{"id":2,"name":"Döner","options":["scharf"],"price":7.5,"qty":1,"note":"ohne Zwiebeln"},
                    {"id":2,"name":"Döner","options":["scharf"],"price":7.5,"qty":1,"note":"extra Soße"}]""",
            )
        assertEquals(listOf("ohne Zwiebeln", "extra Soße"), lines.map { it.dishNote })
        // And the Reklamation names the one that was missing.
        val issue = OrderIssue(id = "i", kindWire = "missing", lines = listOf(1), orders = IssueOrder(items = lines))
        assertEquals("1× Döner (extra Soße)", OrderIssues.linesLabel(issue))
    }
}
