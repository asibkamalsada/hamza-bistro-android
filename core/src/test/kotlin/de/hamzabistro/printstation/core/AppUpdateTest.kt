package de.hamzabistro.printstation.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse

class AppReleasesTest {
    /** /releases/latest as GitHub answered it for v0.2.30, cut down to what is read and a little more. */
    private fun release(
        tag: String = "v0.2.30",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String =
            """[{"name":"hamza-team-0.2.30.apk","content_type":"application/vnd.android.package-archive",""" +
                """"browser_download_url":"https://github.com/asibkamalsada/hamza-bistro-android/releases/download/v0.2.30/hamza-team-0.2.30.apk"}]""",
    ) =
        """{"tag_name":"$tag","name":"Hamza Team 0.2.30","draft":$draft,"prerelease":$prerelease,""" +
            """"html_url":"https://github.com/asibkamalsada/hamza-bistro-android/releases/tag/v0.2.30",""" +
            """"published_at":"2026-10-02T07:09:36Z","assets":$assets,"body":${json.encodeToString(NOTES)}}"""

    @Test
    fun `reads the run number off CI's tags`() {
        assertEquals(57, AppReleases.versionCode("v0.2.57"))
        assertEquals(57, AppReleases.versionCode("0.2.57"))
        assertEquals(1203, AppReleases.versionCode("v1.0.1203"))
        assertNull(AppReleases.versionCode("v0.2"))
        assertNull(AppReleases.versionCode("nightly"))
        assertNull(AppReleases.versionCode("v0.2.57-beta"))
    }

    @Test
    fun `reads the release CI published`() {
        val release = AppReleases.parse(release())!!
        assertEquals(30, release.versionCode)
        assertEquals("0.2.30", release.versionName)
        assertEquals(
            "https://github.com/asibkamalsada/hamza-bistro-android/releases/download/v0.2.30/hamza-team-0.2.30.apk",
            release.downloadUrl,
        )
        assertEquals(release.downloadUrl, release.apkUrl)
        assertEquals(listOf("Add staff management screens: menu, hours, health, and history", "Fix the printer"), release.changes)
    }

    @Test
    fun `falls back to the release's page without an APK`() {
        val release = AppReleases.parse(release(assets = """[{"name":"notes.txt","browser_download_url":"https://example.org/notes.txt"}]"""))!!
        assertEquals("https://github.com/asibkamalsada/hamza-bistro-android/releases/tag/v0.2.30", release.downloadUrl)
        assertNull(release.apkUrl)
    }

    @Test
    fun `never offers a download over plain http`() {
        val release = AppReleases.parse(release(assets = """[{"name":"a.apk","browser_download_url":"http://example.org/a.apk"}]"""))!!
        assertEquals("https://github.com/asibkamalsada/hamza-bistro-android/releases/tag/v0.2.30", release.downloadUrl)
        assertNull(release.apkUrl)
    }

    @Test
    fun `ignores what is not a release to install`() {
        assertNull(AppReleases.parse(release(draft = true)))
        assertNull(AppReleases.parse(release(prerelease = true)))
        assertNull(AppReleases.parse(release(tag = "nightly")))
        assertNull(AppReleases.parse("""{"message":"Not Found"}"""))
        assertNull(AppReleases.parse("<html>rate limited</html>"))
        assertNull(AppReleases.parse("[]"))
    }

    @Test
    fun `lists the merged pull requests, at most ten`() {
        val many = (1..15).joinToString("\n") { "* Change $it by @a in https://github.com/x/y/pull/$it" }
        assertEquals((1..10).map { "Change $it" }, AppReleases.changes(many))
        assertEquals(emptyList(), AppReleases.changes("Built from abc.\n\n**Full Changelog**: https://x"))
    }

    private companion object {
        val NOTES =
            """
            Built from 6476f22 in [this run](https://github.com/asibkamalsada/hamza-bistro-android/actions/runs/1). Open the APK on the phone or tablet to install it over the version before.

            ## What's Changed
            * Add staff management screens: menu, hours, health, and history by @asibkamalsada in https://github.com/asibkamalsada/hamza-bistro-android/pull/6
            * Fix the printer

            **Full Changelog**: https://github.com/asibkamalsada/hamza-bistro-android/compare/v0.2.27...v0.2.30
            """
                .trimIndent()
    }
}

class GitHubReleasesTest {
    private val test = TestServer()
    private val github = GitHubReleases(test.client, "HamzaTeam/0.2.29", test.server.url("/repos/x/y/releases/latest").toString())

    @AfterTest fun close() = test.close()

    @Test
    fun `asks GitHub's API as it wants to be asked`() = runBlocking<Unit> {
        test.reply(
            200,
            """{"tag_name":"v0.2.31","html_url":"https://github.com/x/y/releases/tag/v0.2.31",""" +
                """"assets":[{"name":"hamza-team-0.2.31.apk","browser_download_url":"https://github.com/x/y/releases/download/v0.2.31/hamza-team-0.2.31.apk"}]}""",
        )

        val release = github.latest()!!
        assertEquals(31, release.versionCode)

        val request = test.server.takeRequest()
        assertEquals("/repos/x/y/releases/latest", request.url.encodedPath)
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals("HamzaTeam/0.2.29", request.headers["User-Agent"])
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `no release yet is nothing newer`() = runBlocking<Unit> {
        test.reply(404, """{"message":"Not Found"}""")
        assertNull(github.latest())
    }

    @Test
    fun `an error or a rate limit is a failure`() = runBlocking<Unit> {
        test.reply(403, """{"message":"API rate limit exceeded"}""")
        assertFailsWith<IOException> { github.latest() }
        test.reply(502, "")
        assertFailsWith<IOException> { github.latest() }
    }

    @Test
    fun `refuses an answer far longer than a release`() = runBlocking<Unit> {
        test.reply(200, "x".repeat(600 * 1024))
        assertFailsWith<IOException> { github.latest() }
    }
}

class UpdateCheckerTest {
    private var clock = 0L
    private val wall = Instant.parse("2026-10-02T12:00:00Z")
    private var answers = ArrayDeque<() -> AppRelease?>()
    private var asked = 0

    private val source = ReleaseSource {
        asked++
        answers.removeFirst()()
    }

    private val checker = UpdateChecker(source, installed = 30, logger = Logger.NONE, now = { clock }, wallClock = { wall })

    private fun build(code: Int) = AppRelease(code, "0.2.$code", "https://example.org/hamza-team-0.2.$code.apk")

    @Test
    fun `offers only a build newer than the one running`() = runBlocking {
        answers += { build(30) }
        checker.check()
        assertNull(checker.state.value.available)
        assertEquals(wall, checker.state.value.checkedAt)

        answers += { build(31) }
        checker.check()
        assertEquals(31, checker.state.value.available?.versionCode)

        answers += { null }
        checker.check()
        assertNull(checker.state.value.available)
    }

    @Test
    fun `asks again only once the given time has passed`() = runBlocking {
        answers += { build(31) }
        checker.check(UpdateChecker.ON_OPEN)
        assertEquals(1, asked)

        clock += 59.minutes.inWholeMilliseconds
        checker.check(UpdateChecker.ON_OPEN)
        assertEquals(1, asked)

        clock += 1.minutes.inWholeMilliseconds
        answers += { build(32) }
        checker.check(UpdateChecker.ON_OPEN)
        assertEquals(2, asked)
        assertEquals(32, checker.state.value.available?.versionCode)

        clock += 23.hours.inWholeMilliseconds
        checker.check(UpdateChecker.DAY)
        assertEquals(2, asked)
    }

    @Test
    fun `a failure never escapes, and keeps what was found before`() = runBlocking {
        answers += { build(31) }
        checker.check()

        answers += { throw IOException("no network") }
        checker.check()
        assertEquals(31, checker.state.value.available?.versionCode)
        assertTrue(checker.state.value.failing)

        answers += { throw IllegalStateException("anything else") }
        checker.check()
        assertTrue(checker.state.value.failing)

        answers += { build(31) }
        checker.check()
        assertFalse(checker.state.value.failing)
    }

    @Test
    fun `a failed look counts, so a GitHub that is down is not asked at every turn`() = runBlocking {
        answers += { throw IOException("down") }
        checker.check(UpdateChecker.ON_OPEN)
        clock += 10.minutes.inWholeMilliseconds
        checker.check(UpdateChecker.ON_OPEN)
        assertEquals(1, asked)
    }
}

class ApkDownloadTest {
    private val test = TestServer()
    private val dir = Files.createTempDirectory("apk").toFile()
    private val target = File(dir, "hamza-team-31.apk")
    private val url = test.server.url("/x/y/releases/download/v0.2.31/hamza-team-0.2.31.apk").toString()

    @AfterTest
    fun close() {
        test.close()
        dir.deleteRecursively()
    }

    private fun apk(bytes: Int) = test.reply(200, "x".repeat(bytes), "application/vnd.android.package-archive")

    /** The same without a Content-Length: the stream just ends. */
    private fun apkOfUnknownLength(bytes: Int) =
        test.server.enqueue(MockResponse.Builder().code(200).body("x".repeat(bytes)).removeHeader("Content-Length").build())

    @Test
    fun `writes the APK to the file, and says how far it is`() = runBlocking<Unit> {
        apk(300_000)
        val progress = mutableListOf<Float?>()

        ApkDownload(test.client, "HamzaTeam/0.2.30").fetch(url, target) { progress += it }

        assertEquals(300_000L, target.length())
        assertEquals(listOf(target.name), dir.list()!!.toList())
        assertEquals(1f, progress.last())
        assertEquals<List<Float?>>(progress.map { it!! }.sorted(), progress)
        val request = test.server.takeRequest()
        assertEquals("HamzaTeam/0.2.30", request.headers["User-Agent"])
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `says nothing of how far when the server does not say how long`() = runBlocking<Unit> {
        apkOfUnknownLength(5_000)
        val progress = mutableListOf<Float?>()

        ApkDownload(test.client, "HamzaTeam/0.2.30").fetch(url, target) { progress += it }

        assertEquals(5_000L, target.length())
        assertTrue(progress.isNotEmpty() && progress.all { it == null })
    }

    @Test
    fun `takes the place of what was there`() = runBlocking<Unit> {
        target.writeText("the build before")
        apk(2_000)
        ApkDownload(test.client, "HamzaTeam/0.2.30").fetch(url, target) {}
        assertEquals(2_000L, target.length())
    }

    @Test
    fun `an error leaves nothing behind`() = runBlocking<Unit> {
        test.reply(404, "Not Found", "text/plain")
        assertFailsWith<IOException> { ApkDownload(test.client, "HamzaTeam/0.2.30").fetch(url, target) {} }
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `refuses far more than an APK, whether the server says how long or not`() = runBlocking<Unit> {
        val small = ApkDownload(test.client, "HamzaTeam/0.2.30", maxBytes = 1_000)
        apk(5_000)
        assertFailsWith<IOException> { small.fetch(url, target) {} }
        apkOfUnknownLength(5_000)
        assertFailsWith<IOException> { small.fetch(url, target) {} }
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `never fetches over plain http`() = runBlocking<Unit> {
        assertFailsWith<IOException> { ApkDownload(test.client, "HamzaTeam/0.2.30").fetch("http://example.org/a.apk", target) {} }
        assertEquals(0, test.server.requestCount)
    }
}

class UpdateInstallerTest {
    private val dir = File(Files.createTempDirectory("cache").toFile(), "updates")

    private var fetches = 0
    private var failing = false
    private var ours = true
    private val opened = mutableListOf<File>()

    /** What the buttons showed while the APK was on its way. */
    private val shown = mutableListOf<InstallState>()

    /** Holds the fetch until it is completed, for a second tap meanwhile. */
    private var gate: CompletableDeferred<Unit>? = null
    private val fetching = CompletableDeferred<Unit>()

    private val source: ApkSource = ApkSource { _, target, progress ->
        fetches++
        shown += installer.state.value
        fetching.complete(Unit)
        gate?.await()
        if (failing) throw IOException("no network")
        progress(0.5f)
        shown += installer.state.value
        target.writeText("apk")
    }

    private val device =
        object : ApkInstaller {
            override fun isThisApp(apk: File) = ours

            override fun open(apk: File) {
                opened += apk
            }
        }

    private val installer: UpdateInstaller = UpdateInstaller(source, dir, device, Logger.NONE)

    private fun build(code: Int, apk: Boolean = true) =
        AppRelease(
            versionCode = code,
            versionName = "0.2.$code",
            downloadUrl = "https://example.org/releases/tag/v0.2.$code",
            apkUrl = "https://example.org/hamza-team-0.2.$code.apk".takeIf { apk },
        )

    @AfterTest
    fun clear() {
        dir.parentFile.deleteRecursively()
    }

    @Test
    fun `fetches the APK and hands it to Android's installer`() = runBlocking {
        installer.install(build(31))

        assertEquals<List<File>>(listOf(File(dir, "hamza-team-31.apk")), opened)
        assertEquals("apk", opened.single().readText())
        assertEquals<List<InstallState>>(listOf(InstallState.Fetching(null), InstallState.Fetching(0.5f)), shown)
        assertEquals(InstallState.Idle, installer.state.value)
    }

    @Test
    fun `a tap after Android's installer was closed opens what is already here`() = runBlocking {
        installer.install(build(31))
        installer.install(build(31))
        assertEquals(1, fetches)
        assertEquals(2, opened.size)
    }

    @Test
    fun `a newer build takes the place of the one before`() = runBlocking {
        installer.install(build(31))
        installer.install(build(32))
        assertEquals(2, fetches)
        assertEquals(listOf("hamza-team-32.apk"), dir.list()!!.toList())
    }

    @Test
    fun `a failure never escapes, and the next tap tries again`() = runBlocking {
        failing = true
        installer.install(build(31))
        assertEquals(InstallState.Failed, installer.state.value)
        assertTrue(opened.isEmpty())

        failing = false
        installer.install(build(31))
        assertEquals(InstallState.Idle, installer.state.value)
        assertEquals(1, opened.size)
    }

    @Test
    fun `never hands over what is not a build of this app`() = runBlocking {
        ours = false
        installer.install(build(31))
        assertEquals(InstallState.Failed, installer.state.value)
        assertTrue(opened.isEmpty())
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `a release without an APK is left to the browser`() = runBlocking {
        installer.install(build(31, apk = false))
        assertEquals(InstallState.Failed, installer.state.value)
        assertEquals(0, fetches)
    }

    @Test
    fun `a tap while one is on its way does nothing`() = runBlocking {
        val held = CompletableDeferred<Unit>().also { gate = it }
        val first = launch(Dispatchers.Default) { installer.install(build(31)) }
        fetching.await()

        installer.install(build(31))
        assertEquals(1, fetches)
        assertTrue(opened.isEmpty())
        assertEquals(InstallState.Fetching(null), installer.state.value)

        held.complete(Unit)
        first.join()
        assertEquals(1, opened.size)
    }
}
