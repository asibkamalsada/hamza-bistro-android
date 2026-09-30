package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionManagerTest {
    private val test = TestServer()
    private val store = MemorySessionStore()
    private var clock = 0L
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { clock }, store) { clock }

    @AfterTest fun close() = test.close()

    @Test
    fun `signs in with the captcha token and keeps only the refresh token`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val account = sessions.signIn(" drucker@example.com ", "secret", "captcha-token")

        assertEquals("user-1", account.userId)
        val request = test.server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", request.target)
        assertEquals("anon-key", request.headers["apikey"])
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("drucker@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals(
            "captcha-token",
            body["gotrue_meta_security"]!!.jsonObject["captcha_token"]!!.jsonPrimitive.content,
        )
        assertEquals("refresh-1", store.stored?.refreshToken)
        assertEquals(account, sessions.account.value)
        assertEquals("access-1", sessions.accessToken())
    }

    @Test
    fun `refreshes a token about to run out, and keeps the rotated refresh token`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1", expiresIn = 120)
        sessions.signIn("drucker@example.com", "secret", null)
        test.server.takeRequest()

        clock = 30_000 // 90 s left: still good
        assertEquals("access-1", sessions.accessToken())
        clock = 70_000 // 50 s left: refreshed
        test.token("access-2", "refresh-2")
        assertEquals("access-2", sessions.accessToken())

        val refresh = test.server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", refresh.target)
        assertTrue(refresh.body!!.utf8().contains("refresh-1"))
        assertEquals("refresh-2", store.stored?.refreshToken)
    }

    @Test
    fun `a refresh token the server no longer takes signs out`() = runBlocking<Unit> {
        store.stored = StoredSession("revoked", Account("user-1", null))
        test.reply(400, """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""")

        assertFailsWith<SignedOutException> { sessions.accessToken() }
        assertNull(store.stored)
        assertNull(sessions.account.value)
    }

    @Test
    fun `too many tries is a wait, not a sign-out`() = runBlocking<Unit> {
        store.stored = StoredSession("refresh-1", Account("user-1", null))
        test.reply(429, """{"error_code":"over_request_rate_limit","msg":"slow down"}""")

        assertFailsWith<BackendException> { sessions.accessToken() }
        assertNotNull(store.stored)
    }

    @Test
    fun `a wrong password is refused with Supabase's code`() = runBlocking<Unit> {
        test.reply(400, """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
        val refused = assertFailsWith<AuthRejectedException> { sessions.signIn("a@b.c", "wrong", "t") }
        assertEquals("invalid_credentials", refused.code)
        assertNull(store.stored)
    }

    @Test
    fun `signing out ends the session on the server and here`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        sessions.signIn("drucker@example.com", "secret", null)
        test.server.takeRequest()
        test.reply(204)

        sessions.signOut()
        val logout = test.server.takeRequest()
        assertEquals("/auth/v1/logout?scope=local", logout.target)
        assertEquals("Bearer access-1", logout.headers["Authorization"])
        assertNull(store.stored)
        assertFailsWith<SignedOutException> { sessions.accessToken() }
    }
}
