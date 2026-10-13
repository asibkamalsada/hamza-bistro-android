// The print station without Android: the Supabase calls, Realtime, and the
// loop that decides what to print. Plain Kotlin, so it is tested on the JVM
// in seconds, and the app is left with what only Android can do — the
// service, Bluetooth, the Keystore and the screen.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

// What is asked of the shrunk core, below: src/shrunk, written against the
// core as the app is.
val shrunk: SourceSet = sourceSets.create("shrunk") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}

// The shrinker (R8) is inside AGP's builder: the one the release APK is
// made with, and of the same version whenever AGP's changes.
val r8: Configuration = configurations.create("r8") { isCanBeConsumed = false }

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp.coroutines)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)

    r8("com.android.tools.build:builder:${libs.versions.agp.get()}") { isTransitive = false }
}

tasks.test {
    useJUnitPlatform()
}

// The tests above run the classes as compiled; the tablet runs them as the
// release build's shrinker leaves them — renamed, moved out of their
// packages, and without what nothing reaches. So the core and what it is
// built on are put through that shrinker here as well, with the rules the
// release build has, into one jar.
val shrink = tasks.register<JavaExec>("shrink") {
    val program = files(tasks.jar, configurations.runtimeClasspath, shrunk.output.classesDirs.asFileTree.matching { include("**/*.class") })
    val rules = files("shrunk-rules.pro", rootProject.file("app/proguard-rules.pro"))
    val jar = layout.buildDirectory.file("shrunk/core.jar")
    inputs.files(program, rules)
    outputs.file(jar)
    classpath = r8
    mainClass = "com.android.tools.r8.R8"
    args("--classfile", "--lib", System.getProperty("java.home"), "--output", jar.get().asFile.path)
    argumentProviders.add(CommandLineArgumentProvider { rules.flatMap { listOf("--pg-conf", it.path) } + program.map { it.path } })
    // It has a line for every rule that matches nothing here and every
    // manifest it meets twice: shown only when it fails.
    val said = ByteArrayOutputStream()
    standardOutput = said
    errorOutput = said
    isIgnoreExitValue = true
    doLast { check(executionResult.get().exitValue == 0) { "R8 failed:\n$said" } }
}

// … and src/shrunk run from that jar, with nothing else beside it: what
// works as compiled and not once shrunk fails here, not on the tablet.
val shrunkTest = tasks.register<JavaExec>("shrunkTest") {
    classpath = files(shrink)
    mainClass = "de.hamzabistro.printstation.core.ShrunkKt"
}

tasks.check {
    dependsOn(shrunkTest)
}
