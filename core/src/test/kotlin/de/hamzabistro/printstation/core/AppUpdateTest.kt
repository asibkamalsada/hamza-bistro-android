package de.hamzabistro.printstation.core

import java.io.IOException
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
import kotlinx.coroutines.runBlocking

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
        assertEquals(listOf("Add staff management screens: menu, hours, health, and history", "Fix the printer"), release.changes)
    }

    @Test
    fun `falls back to the release's page without an APK`() {
        val release = AppReleases.parse(release(assets = """[{"name":"notes.txt","browser_download_url":"https://example.org/notes.txt"}]"""))!!
        assertEquals("https://github.com/asibkamalsada/hamza-bistro-android/releases/tag/v0.2.30", release.downloadUrl)
    }

    @Test
    fun `never offers a download over plain http`() {
        val release = AppReleases.parse(release(assets = """[{"name":"a.apk","browser_download_url":"http://example.org/a.apk"}]"""))!!
        assertEquals("https://github.com/asibkamalsada/hamza-bistro-android/releases/tag/v0.2.30", release.downloadUrl)
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
