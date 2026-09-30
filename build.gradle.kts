// Declared here once, applied in the modules. The Kotlin plugins also set the
// Kotlin version the app builds with: AGP 9 compiles Kotlin by itself, with
// the Kotlin Gradle plugin it finds on this classpath.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
