package de.hamzabistro.printstation.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync

/**
 * A build of this app that CI published as a GitHub release: tagged
 * v0.2.<run>, its APK attached. The run number is the build's versionCode,
 * so it is what decides which of two builds is newer.
 */
data class AppRelease(
    val versionCode: Int,
    /** "0.2.57", as the tag has it without its "v". */
    val versionName: String,
    /** For the browser: the APK itself, or the release's page when it has none. https either way. */
    val downloadUrl: String,
    /** The merged pull requests CI wrote into the notes, one line each. */
    val changes: List<String> = emptyList(),
    /** The APK, for the app to fetch by itself; null when the release has none, only its page. */
    val apkUrl: String? = null,
)

/** Reading the latest release; GitHub's answer, without the network. */
object AppReleases {
    /** Unauthenticated: the repository and its releases are public. */
    const val LATEST_URL = "https://api.github.com/repos/asibkamalsada/hamza-bistro-android/releases/latest"

    private val TAG = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

    /** "by @someone in https://…/pull/6" after a pull request's title in GitHub's generated notes. */
    private val CHANGE = Regex("""^[*-]\s+(.+?)(?:\s+by\s+@\S+)?(?:\s+in\s+https://\S+)?\s*$""")

    private const val MAX_CHANGES = 10

    /** The run number in a tag such as "v0.2.57", or null when the tag is not one of CI's. */
    fun versionCode(tag: String): Int? = TAG.matchEntire(tag.trim())?.groupValues?.get(3)?.toIntOrNull()

    /**
     * The release in GitHub's answer to /releases/latest; null for one this
     * app cannot use — a draft, a pre-release, a tag not of CI's making, or
     * nothing to download over https.
     */
    fun parse(body: String): AppRelease? {
        val release = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (release.flag("draft") || release.flag("prerelease")) return null
        val tag = release.string("tag_name") ?: return null
        val code = versionCode(tag) ?: return null
        val apk =
            (release["assets"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.string("name").orEmpty().endsWith(".apk", ignoreCase = true) }
                .firstNotNullOfOrNull { https(it.string("browser_download_url")) }
        val download = apk ?: https(release.string("html_url")) ?: return null
        return AppRelease(
            versionCode = code,
            versionName = tag.trim().removePrefix("v"),
            downloadUrl = download,
            changes = changes(release.string("body").orEmpty()),
            apkUrl = apk,
        )
    }

    /** The bullet lines of the notes — the merged pull requests — without who merged them where. */
    fun changes(notes: String): List<String> =
        notes
            .lineSequence()
            .mapNotNull { CHANGE.matchEntire(it.trim())?.groupValues?.get(1)?.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_CHANGES)
            .toList()

    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

    private fun https(url: String?): String? = url?.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()
}

/** Where the latest release is read from. */
fun interface ReleaseSource {
    /** The latest usable release, or null when there is none. Throws when it could not be read. */
    suspend fun latest(): AppRelease?
}

/** [ReleaseSource] over GitHub's REST API, without an account. */
class GitHubReleases(
    private val http: OkHttpClient,
    /** Sent as the User-Agent, which GitHub asks every caller for. */
    private val userAgent: String,
    private val url: String = AppReleases.LATEST_URL,
) : ReleaseSource {
    override suspend fun latest(): AppRelease? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", userAgent)
                    .build()
            http.newCall(request).executeAsync().use { response ->
                // No release published yet: nothing newer, not a failure.
                if (response.code == 404) return@use null
                if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
                val source = response.body.source()
                // A release is a few kilobytes; more is not GitHub's answer.
                if (source.request(MAX_BYTES + 1)) throw IOException("GitHub: answer too long")
                AppReleases.parse(source.buffer.readUtf8())
            }
        }

    private companion object {
        const val MAX_BYTES = 512L * 1024
    }
}

/** What is known about a newer build, for the queue and the settings. */
data class UpdateState(
    /** A release newer than the build running here; null when there is none, or none known. */
    val available: AppRelease? = null,
    /** When GitHub last answered. */
    val checkedAt: Instant? = null,
    /** The last look did not get an answer; [available] is still what the one before found. */
    val failing: Boolean = false,
)

/**
 * Looks for a newer build than [installed] now and then: once a day while
 * on shift, and when the app is opened. Off the alarm's path on purpose —
 * it never throws, holds nothing the queue or the printer waits on, and a
 * failure only leaves [state] as it was, marked [UpdateState.failing].
 */
class UpdateChecker(
    private val source: ReleaseSource,
    private val installed: Int,
    private val logger: Logger,
    /** Milliseconds that only go forward: for how long ago the last look was. */
    private val now: () -> Long,
    private val wallClock: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val lock = Mutex()
    private var lastLook: Long? = null

    /**
     * Asks GitHub, unless it was asked less than [maxAge] ago — answered or
     * not, so a GitHub that is down is not asked again at every turn.
     * Several callers at once make one request.
     */
    suspend fun check(maxAge: Duration = Duration.ZERO) {
        lock.withLock {
            val last = lastLook
            if (last != null && now() - last < maxAge.inWholeMilliseconds) return
            lastLook = now()
            try {
                val latest = source.latest()
                _state.value =
                    UpdateState(available = latest?.takeIf { it.versionCode > installed }, checkedAt = wallClock(), failing = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Looking for a newer build failed: ${e.javaClass.simpleName}")
                _state.value = _state.value.copy(failing = true)
            }
        }
    }

    /** Once a day for as long as it runs: for the service, while on shift. */
    suspend fun daily(): Nothing {
        while (true) {
            check(DAY)
            delay(WAKE)
        }
    }

    companion object {
        val DAY: Duration = 1.days

        /** How often [daily] looks at whether a day has passed: the service may sleep through some. */
        private val WAKE: Duration = 1.hours

        /** Opening the app looks again only after this long. */
        val ON_OPEN: Duration = 1.hours
    }
}

/** Where a release's APK is fetched from, into a file on the device. */
fun interface ApkSource {
    /**
     * Writes the APK at [url] to [target], telling [progress] how far it is
     * from 0 to 1 — null when the server did not say how long it is.
     * [target] is there only once it is whole. Throws when it could not be
     * fetched.
     */
    suspend fun fetch(url: String, target: File, progress: (Float?) -> Unit)
}

/** [ApkSource] over HTTPS, as GitHub hands out a release's files: without an account. */
class ApkDownload(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val maxBytes: Long = MAX_BYTES,
) : ApkSource {
    override suspend fun fetch(url: String, target: File, progress: (Float?) -> Unit) {
        withContext(Dispatchers.IO) {
            val address = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: throw IOException("APK: not an https address")
            // Beside the target until it is whole, so half an APK is never taken for one.
            val part = File(target.path + ".part")
            try {
                http.newCall(Request.Builder().url(address).header("User-Agent", userAgent).build()).executeAsync().use { response ->
                    if (!response.isSuccessful) throw IOException("APK: HTTP ${response.code}")
                    val length = response.body.contentLength().takeIf { it > 0 }
                    if (length != null && length > maxBytes) throw IOException("APK: too long")
                    response.body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val chunk = ByteArray(CHUNK)
                            var received = 0L
                            while (true) {
                                ensureActive()
                                val read = input.read(chunk)
                                if (read < 0) break
                                received += read
                                // Whatever the length header said, or did not say.
                                if (received > maxBytes) throw IOException("APK: too long")
                                output.write(chunk, 0, read)
                                progress(length?.let { (received.toFloat() / it).coerceAtMost(1f) })
                            }
                        }
                    }
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                part.delete()
            }
        }
    }

    private companion object {
        /** The APK is a few megabytes; far more is not it. */
        const val MAX_BYTES = 200L * 1024 * 1024

        const val CHUNK = 64 * 1024
    }
}

/** How far fetching a newer build is, for the buttons that start it. */
sealed interface InstallState {
    /** Nothing on its way: "Installieren" starts it. */
    data object Idle : InstallState

    /** [progress] from 0 to 1; null when the server did not say how long the APK is. */
    data class Fetching(val progress: Float?) : InstallState

    /** It did not get as far as Android's installer; the browser is still there. */
    data object Failed : InstallState
}

/** What only the device can do with a fetched APK. */
interface ApkInstaller {
    /** Whether [apk] is a build of the app running here, so that installing it can only replace it. */
    fun isThisApp(apk: File): Boolean

    /** Android's installer over [apk], which asks the person first. Throws when it cannot be shown. */
    fun open(apk: File)
}

/**
 * "Installieren" without a browser: fetches a release's APK into [dir] and
 * hands it to Android's installer. Off the alarm's path like [UpdateChecker]
 * — it never throws, and a failure only shows as [InstallState.Failed].
 */
class UpdateInstaller(
    private val source: ApkSource,
    /** Holds the one APK last fetched, and nothing else: it is emptied for the next. */
    private val dir: File,
    private val device: ApkInstaller,
    private val logger: Logger,
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val lock = Mutex()

    /**
     * Fetches [release]'s APK — unless it is here already, from a tap before
     * — and opens Android's installer over it. A tap while one is on its way
     * does nothing.
     */
    suspend fun install(release: AppRelease) {
        if (!lock.tryLock()) return
        try {
            _state.value = InstallState.Fetching(null)
            device.open(withContext(Dispatchers.IO) { fetched(release) })
            _state.value = InstallState.Idle
        } catch (e: CancellationException) {
            _state.value = InstallState.Idle
            throw e
        } catch (e: Exception) {
            logger.warn("Fetching the newer build failed", e)
            _state.value = InstallState.Failed
        } finally {
            lock.unlock()
        }
    }

    private suspend fun fetched(release: AppRelease): File {
        val url = release.apkUrl ?: throw IOException("the release has no APK")
        // Named by the run number, not the tag: nothing a release says gets into a path.
        val apk = File(dir, "hamza-team-${release.versionCode}.apk")
        if (!apk.isFile) {
            // The build before this one, or half of one: one APK at a time.
            dir.deleteRecursively()
            dir.mkdirs()
            source.fetch(url, apk) { _state.value = InstallState.Fetching(it) }
        }
        if (!device.isThisApp(apk)) {
            apk.delete()
            throw IOException("not a build of this app")
        }
        return apk
    }
}
