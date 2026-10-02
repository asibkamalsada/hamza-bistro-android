package de.hamzabistro.printstation.core

import java.time.Duration
import java.time.Instant

/** How close an order is to being declined unanswered. */
enum class DeclineUrgency {
    CALM,

    /** Under five minutes left. */
    SOON,

    /** Under two: the card goes red, and the alarm has escalated. */
    URGENT,
}

/**
 * What a waiting card says about its deadline. [showTime]: more than an hour
 * off, so the card names [deadline] rather than "in 1440 Min.". [minutesLeft]
 * 0 or less: any moment now.
 */
data class DeclineCountdown(
    val deadline: Instant,
    val minutesLeft: Long,
    val showTime: Boolean,
    val urgency: DeclineUrgency,
)

/**
 * Orders nobody answers are declined by the database
 * (20261002150000_auto_decline.sql in hamza-bistro-web): the card counts
 * down to that moment, as declineTiming() of the site's orders-page.ts
 * does, and the alarm escalates once just before it.
 *
 * The deadline is the server's own (auto_decline_at, a computed column), so
 * every device counts to the moment the job uses. The queue is read again
 * on every realtime change, so it is never a stale copy.
 */
object AutoDecline {
    /** The choices under Lieferzeiten, null being off — AUTO_DECLINE_CHOICES of the site. */
    val CHOICES: List<Int?> = listOf(null, 5, 10, 15, 20, 30)

    /** Marked "empfohlen". */
    const val SUGGESTED = 10

    /** What set_auto_decline_minutes() takes besides off. */
    val ALLOWED: IntRange = 3..60

    /** When the alarm escalates, and the card turns red. */
    val ESCALATE_BEFORE: Duration = Duration.ofMinutes(2)

    private const val SHOW_TIME_AFTER_MINUTES = 60
    private const val SOON_MINUTES = 5

    /** The countdown for a waiting order; null for any other, or with auto-decline off. */
    fun countdown(order: StaffOrder, now: Instant): DeclineCountdown? {
        if (order.status != OrderStatus.NEW) return null
        val at = order.autoDeclineAt ?: return null
        val left = StaffQueue.minutesUntil(at, now)
        val urgency =
            when {
                left < ESCALATE_BEFORE.toMinutes() -> DeclineUrgency.URGENT
                left < SOON_MINUTES -> DeclineUrgency.SOON
                else -> DeclineUrgency.CALM
            }
        val far = left > SHOW_TIME_AFTER_MINUTES
        return DeclineCountdown(at, left, far, if (far) DeclineUrgency.CALM else urgency)
    }

    /**
     * Whether the alarm should escalate for [order] at [now]: still waiting,
     * two minutes or less from its deadline, and the deadline not yet past
     * — after it, the job declines it within seconds and there is nothing
     * left to hurry.
     */
    fun escalationDue(order: StaffOrder, now: Instant): Boolean {
        if (order.status != OrderStatus.NEW) return false
        val at = order.autoDeclineAt ?: return false
        return now.isBefore(at) && !now.isBefore(at.minus(ESCALATE_BEFORE))
    }

    /** The choices to offer, with a saved value that is not among them (set on the site, say) kept in. */
    fun choices(saved: Int?): List<Int?> =
        if (saved in CHOICES) CHOICES else (CHOICES + saved).sortedBy { it ?: 0 }
}
