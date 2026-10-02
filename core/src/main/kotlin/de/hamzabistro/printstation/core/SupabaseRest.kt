package de.hamzabistro.printstation.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync

/**
 * PostgREST and the edge functions as the signed-in account: every request
 * carries the account's own token, so the database's policies decide what
 * it may do. Shared by the print station and the staff queue.
 */
internal class SupabaseRest(
    val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val sessions: SessionManager,
) {
    fun endpoint(path: String): HttpUrl.Builder = config.endpoint(path)

    /** A database function; its answer as JSON text ("" for none). */
    suspend fun rpc(name: String, args: JsonObject): String {
        val url = endpoint("rest/v1/rpc/$name").build()
        return call({ it.url(url).post(args.toRequestBody()) }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rejected(response.code, body, name)
            body
        }
    }

    /**
     * Sends a request as the signed-in account. A 401 is tried once more
     * with a fresh token: the one it had may have run out on the way.
     * [read] reads the body, which blocks, hence the IO dispatcher.
     */
    suspend fun <T> call(build: (Request.Builder) -> Request.Builder, read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val token = sessions.accessToken()
            val first = send(build, token)
            if (first.code != 401) return@withContext first.use(read)
            first.close()
            sessions.expire(token)
            send(build, sessions.accessToken()).use(read)
        }

    private suspend fun send(build: (Request.Builder) -> Request.Builder, token: String): Response {
        val request =
            build(Request.Builder())
                .header("apikey", config.anonKey)
                .header("Authorization", "Bearer $token")
                .build()
        return http.newCall(request).executeAsync()
    }

    fun rejected(status: Int, body: String, what: String): Exception {
        val message = errorField(body, "message", "msg", "error") ?: "HTTP $status"
        // How the staff-only and printing functions refuse an account that
        // may not do what it asked.
        if (message == "staff only") return NotAllowedException()
        return when (val code = errorField(body, "code")) {
            // An order for a delivery the shop is not making right now:
            // whoever asked can still make it a collection.
            DELIVERY_PAUSED -> DeliveryPausedException("$what: $message")
            DELIVERY_BREAK -> DeliveryBreakException("$what: $message")
            else -> BackendException(status, "$what: $message", code)
        }
    }

    private companion object {
        /** See 20261002180000_scoped_pause.sql in hamza-bistro-web. */
        const val DELIVERY_PAUSED = "HB436"
        const val DELIVERY_BREAK = "HB437"
    }
}
