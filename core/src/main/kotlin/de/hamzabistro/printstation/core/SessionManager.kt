package de.hamzabistro.printstation.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What of a session is kept across restarts: never the password. */
class StoredSession(val refreshToken: String, val account: Account) {
    override fun toString(): String = "StoredSession(${account.userId})"
}

/**
 * Keeps the refresh token across restarts. The app's implementation encrypts
 * it with a key in the Android Keystore that never leaves the device.
 */
interface SessionStore {
    fun load(): StoredSession?

    fun save(session: StoredSession)

    fun clear()
}

/**
 * The one session the station runs on. Hands out an access token that is
 * good for at least another minute, refreshing it when it is not, and keeps
 * the rotated refresh token.
 */
class SessionManager(
    private val auth: SupabaseAuth,
    private val store: SessionStore,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private var session: Session? = null
    private val _account = MutableStateFlow(store.load()?.account)

    /** Who is signed in, or null. */
    val account: StateFlow<Account?> = _account.asStateFlow()

    suspend fun signIn(email: String, password: String, captchaToken: String?): Account {
        val fresh = auth.signIn(email, password, captchaToken)
        lock.withLock { keep(fresh) }
        return fresh.account
    }

    /** A current access token. Throws [SignedOutException] when there is no session left. */
    suspend fun accessToken(): String =
        lock.withLock {
            session?.takeIf { it.expiresAt - now() > MARGIN_MS }?.let { return it.accessToken }
            val stored = store.load() ?: throw signedOut(null)
            val fresh =
                try {
                    auth.refresh(stored.refreshToken)
                } catch (e: AuthRejectedException) {
                    throw signedOut(e)
                }
            keep(fresh)
            fresh.accessToken
        }

    /**
     * The server refused [token] — expired early, or the clock was off. The
     * next [accessToken] refreshes instead of handing it out again.
     */
    suspend fun expire(token: String) {
        lock.withLock { if (session?.accessToken == token) session = null }
    }

    /**
     * Forgets the session here and ends it on the server, so the refresh
     * token is worthless even if it was copied off the device. The server
     * part is best effort: offline, the session is still gone from here.
     */
    suspend fun signOut() {
        val token = runCatching { accessToken() }.getOrNull()
        lock.withLock {
            session = null
            store.clear()
            _account.value = null
        }
        if (token != null) runCatching { auth.signOut(token) }
    }

    private fun keep(fresh: Session) {
        session = fresh
        store.save(StoredSession(fresh.refreshToken, fresh.account))
        _account.value = fresh.account
    }

    private fun signedOut(cause: Throwable?): SignedOutException {
        session = null
        store.clear()
        _account.value = null
        return SignedOutException(cause)
    }

    private companion object {
        /** An access token closer than this to running out is refreshed first. */
        const val MARGIN_MS = 60_000L
    }
}
