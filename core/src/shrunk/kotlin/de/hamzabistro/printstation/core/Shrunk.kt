package de.hamzabistro.printstation.core

/**
 * Run from the core as the release build's shrinker leaves it (:core:shrunkTest,
 * in core/build.gradle.kts): what the app asks of the core that depends on
 * where a class is or what it is called, asked here the way the app asks.
 *
 * 0.2.159 closed on opening: its shrunk StreetDirectory looked for the
 * street list beside itself, in a package it was no longer in — and every
 * test passed, on classes that were where they were written.
 */
fun main() {
    // As "Neue Bestellung" does on opening, and then on the first letters typed.
    val found = StreetDirectory.matcher.match("karl heine 9").firstOrNull()?.fullStreet
    check(found == "Karl-Heine-Straße 9") { "\"karl heine 9\" found $found among the built-in streets" }
}
