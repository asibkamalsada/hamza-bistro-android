plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** A build setting: -P, then ORG_GRADLE_PROJECT_…, then gradle.properties. */
fun setting(name: String): String = providers.gradleProperty("hamza.$name").getOrElse("")

/** A string as Java source, for buildConfigField. */
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * The release key, from the environment only — CI's secrets, or a shell on
 * the machine that holds the keystore. Without it, a release build is left
 * unsigned rather than signed with something else.
 */
val releaseKeystore: String? = System.getenv("HB_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "de.hamzabistro.printstation"
    // What the current AndroidX and OkHttp releases are built against.
    compileSdk = 37

    defaultConfig {
        applicationId = "de.hamzabistro.printstation"
        // Android 12: the Bluetooth permissions that need no location, and
        // the foreground service rules this is written for.
        minSdk = 31
        // Android 16's behaviour, which the service and the Bluetooth code
        // are written and checked against. Raise it on purpose, not along
        // with compileSdk.
        targetSdk = 36
        val build = providers.gradleProperty("versionCode").getOrElse("1").toInt()
        versionCode = build
        // CI's run number as the last part, so the version the tablet shows
        // under App info is the name of the release it came from.
        versionName = providers.gradleProperty("versionName").getOrElse("0.1.$build")

        buildConfigField("String", "SUPABASE_URL", quoted(setting("supabaseUrl")))
        buildConfigField("String", "SUPABASE_KEY", quoted(setting("supabaseKey")))
        buildConfigField("String", "TURNSTILE_SITE_KEY", quoted(setting("turnstileSiteKey")))
        buildConfigField("String", "TURNSTILE_ORIGIN", quoted(setting("turnstileOrigin")))
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("HB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HB_KEY_ALIAS")
                keyPassword = System.getenv("HB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Beside the release on one tablet, never in its place.
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // The dependency list Google Play reads is of no use to an APK that never
    // goes there, so it is not put in one.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
