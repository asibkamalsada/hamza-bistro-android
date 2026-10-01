package de.hamzabistro.printstation.core

import java.time.Instant

/** An order for a test, accepted for right away unless told otherwise. */
fun order(
    id: String,
    status: OrderStatus = OrderStatus.CONFIRMED,
    createdAt: String = "2026-09-26T16:00:00Z",
    confirmedAt: String? = "2026-09-26T16:02:00Z",
    scheduledFor: String? = null,
    etaMinutes: Int? = 30,
    pickup: Boolean = false,
    number: Long = 57,
) =
    StaffOrder(
        id = id,
        orderNumber = number,
        createdAt = Instant.parse(createdAt),
        confirmedAt = confirmedAt?.let(Instant::parse),
        scheduledFor = scheduledFor?.let(Instant::parse),
        status = status,
        etaMinutes = etaMinutes,
        pickup = pickup,
    )

fun at(text: String): Instant = Instant.parse(text)
