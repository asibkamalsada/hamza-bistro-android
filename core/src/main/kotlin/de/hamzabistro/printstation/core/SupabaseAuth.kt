package de.hamzabistro.printstation.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync

/**
 * A signed-in session. Held in memory only; what outlives the process is the
 * refresh token, encrypted (see [SessionStore]).
 */
class Session(
    val accessToken: String,
    val refreshToken: String,
    /** When the access token runs out, on the [SessionManager]'s clock. */
    val expiresAt: Long,
    val account: Account,
) {
    // Never the tokens, wherever a session ends up printed.
    override fun toString(): String = "Session(${account.userId})"
}

/** Who is signed in. */
data class Account(val userId: String, val email: String?)

/**
 * Supabase Auth, the three calls the station needs: sign in with a password,
 * refresh, and sign out.
 */
class SupabaseAuth(
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val now: () -> Long,
) {
    /**
     * Signs in with email and password. [captchaToken] is the Turnstile
     * token when the project has captcha protection on, as the site's does:
     * Supabase refuses a password sign-in without one.
     */
    suspend fun signIn(email: String, password: String, captchaToken: String?): Session =
        token(
            "password",
            buildJsonObject {
                put("email", email.trim())
                put("password", password)
                if (captchaToken != null) {
                    putJsonObject("gotrue_meta_security") { put("captcha_token", captchaToken) }
                }
            },
        )

    /** A new access token, and a new refresh token: Supabase rotates them. */
    suspend fun refresh(refreshToken: String): Session =
        token("refresh_token", buildJsonObject { put("refresh_token", refreshToken) })

    /** Ends this session on the server, so its refresh token is worth nothing. */
    suspend fun signOut(accessToken: String) {
        val request =
            Request.Builder()
                .url(config.endpoint("auth/v1/logout").addQueryParameter("scope", "local").build())
                .header("apikey", config.anonKey)
                .header("Authorization", "Bearer $accessToken")
                .post(buildJsonObject {}.toRequestBody())
                .build()
        http.newCall(request).executeAsync().use { response ->
            // Already ended is as good as ended.
            if (!response.isSuccessful && response.code !in setOf(401, 403, 404)) {
                throw BackendException(response.code, "sign-out: HTTP ${response.code}")
            }
        }
    }

    private suspend fun token(grant: String, body: kotlinx.serialization.json.JsonObject): Session {
        val request =
            Request.Builder()
                .url(config.endpoint("auth/v1/token").addQueryParameter("grant_type", grant).build())
                .header("apikey", config.anonKey)
                .post(body.toRequestBody())
                .build()
        val asked = now()
        http.newCall(request).executeAsync().use { response ->
            val text = response.body.string()
            if (response.isSuccessful) {
                val token = json.decodeFromString(TokenResponse.serializer(), text)
                return Session(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    // From when it was asked for, not the server's clock: the
                    // tablet's may be off, and early is the safe side.
                    expiresAt = asked + token.expiresIn * 1000,
                    account = Account(token.user.id, token.user.email),
                )
            }
            val code = errorField(text, "error_code", "error")
            val message = errorField(text, "msg", "error_description", "message") ?: "HTTP ${response.code}"
            // Too many tries is a moment's wait, not a wrong password.
            if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                throw AuthRejectedException(code, message)
            }
            throw BackendException(response.code, "auth: $message")
        }
    }

    @Serializable
    private class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long,
        val user: User,
    )

    @Serializable private class User(val id: String, val email: String? = null)
}
