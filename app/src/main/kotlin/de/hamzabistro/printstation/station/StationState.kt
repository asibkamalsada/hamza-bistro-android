package de.hamzabistro.printstation.station

import de.hamzabistro.printstation.core.StationStatus

/** What the print station service is doing, as the screen shows it. */
sealed interface StationState {
    data object Stopped : StationState

    data class Running(val status: StationStatus) : StationState

    /** The session ended on the server: printing stopped until somebody signs in again. */
    data object SignedOut : StationState

    /** It could not start at all — a permission taken away, no printer chosen. */
    data class Failed(val reason: String) : StationState
}
