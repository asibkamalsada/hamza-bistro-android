package de.hamzabistro.printstation.core

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Says within a second that an order changed, over Supabase Realtime — the
 * same postgres_changes subscription the site's queue has.
 *
 * Only ever a hint to look: what it carries is not read (it would be the
 * order, name and address included), and the poll of whoever listens stays
 * underneath for a socket that died without saying so. The database sends
 * only the rows the signed-in account may read: for a print account, the
 * accepted orders not printed yet.
 */
class OrdersRealtime(
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val sessions: SessionManager,
    private val logger: Logger,
    private val now: () -> Long,
    private val heartbeat: Duration = 25.seconds,
) {
    /**
     * Emits whenever an order that [what] covers may have changed, and once
     * every time the socket has (re)joined, for whatever happened while it
     * was down. Reconnects for as long as it is collected; ends only with a
     * [SignedOutException].
     */
    fun changes(what: Watch = Watch.ACCEPTED): Flow<Unit> = channelFlow {
        var backoff = MIN_BACKOFF
        while (true) {
            val started = now()
            try {
                watch(what) { send(Unit) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignedOutException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Realtime dropped: ${e.message}")
            }
            // A socket that stayed up a while is not a server in trouble.
            if ((now() - started).milliseconds > MAX_BACKOFF) backoff = MIN_BACKOFF
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    /** One socket, from connecting until it closes or fails. */
    private suspend fun watch(watch: Watch, onChange: suspend () -> Unit) {
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val url =
            config.endpoint("realtime/v1/websocket")
                .addQueryParameter("apikey", config.anonKey)
                .addQueryParameter("vsn", "1.0.0")
                .build()
        val socket =
            http.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        frames.trySend(Frame.Open)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        frames.trySend(Frame.Text(text))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        frames.trySend(Frame.Closed("closed by the server ($code)"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        frames.trySend(Frame.Closed(t.message ?: t.javaClass.simpleName))
                    }
                },
            )
        try {
            if (withTimeoutOrNull(heartbeat) { frames.receive() } != Frame.Open) {
                throw IOException("could not connect")
            }
            var ref = 0
            fun nextRef() = (++ref).toString()

            var token = sessions.accessToken()
            val joinRef = nextRef()
            socket.send(join(watch, joinRef, token))
            var joined = false
            var awaitedBeat: String? = null
            var nextBeat = now() + heartbeat.inWholeMilliseconds

            while (true) {
                val wait = (nextBeat - now()).coerceAtLeast(0)
                val frame = withTimeoutOrNull(wait) { frames.receive() }
                if (frame == null) {
                    // Phoenix closes a socket it has not heard from for a
                    // while; one that did not answer the last beat is gone.
                    if (awaitedBeat != null) throw IOException("no answer to the heartbeat")
                    if (!joined) throw IOException("the subscription was not confirmed")
                    val fresh = sessions.accessToken()
                    if (fresh != token) {
                        token = fresh
                        socket.send(accessToken(watch, nextRef(), joinRef, token))
                    }
                    awaitedBeat = nextRef()
                    socket.send(beat(awaitedBeat))
                    nextBeat = now() + heartbeat.inWholeMilliseconds
                    continue
                }
                val text = (frame as? Frame.Text)?.text ?: throw IOException((frame as Frame.Closed).why)
                val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val event = message.string("event")
                val topic = message.string("topic")
                val reply = message["payload"] as? JsonObject
                val status = reply?.string("status")
                when {
                    event == "phx_reply" && message.string("ref") == awaitedBeat -> awaitedBeat = null
                    event == "phx_reply" && message.string("ref") == joinRef -> {
                        // The refusal's reason says nothing about an order:
                        // it is safe to log, the rest of a message may not be.
                        if (status != "ok") throw IOException("subscription refused: ${reply?.get("response")}")
                        joined = true
                        logger.info("Realtime subscribed")
                        onChange()
                    }
                    topic != watch.topic -> Unit
                    event == "postgres_changes" -> onChange()
                    event == "phx_error" || event == "phx_close" -> throw IOException("channel $event")
                    event == "system" && status == "error" ->
                        throw IOException("realtime: ${reply.string("message")}")
                }
            }
        } finally {
            socket.cancel()
            frames.close()
        }
    }

    private fun join(watch: Watch, ref: String, token: String): String =
        buildJsonObject {
                put("topic", watch.topic)
                put("event", "phx_join")
                putJsonObject("payload") {
                    putJsonObject("config") {
                        putJsonObject("broadcast") {
                            put("ack", false)
                            put("self", false)
                        }
                        putJsonObject("presence") {
                            put("key", "")
                            put("enabled", false)
                        }
                        putJsonArray("postgres_changes") {
                            addJsonObject {
                                put("event", "*")
                                put("schema", "public")
                                put("table", "orders")
                                watch.filter?.let { put("filter", it) }
                            }
                        }
                        put("private", false)
                    }
                    put("access_token", token)
                }
                put("ref", ref)
                put("join_ref", ref)
            }
            .toString()

    private fun accessToken(watch: Watch, ref: String, joinRef: String, token: String): String =
        buildJsonObject {
                put("topic", watch.topic)
                put("event", "access_token")
                putJsonObject("payload") { put("access_token", token) }
                put("ref", ref)
                put("join_ref", joinRef)
            }
            .toString()

    private fun beat(ref: String): String =
        buildJsonObject {
                put("topic", "phoenix")
                put("event", "heartbeat")
                put("payload", JsonObject(emptyMap()))
                put("ref", JsonPrimitive(ref))
            }
            .toString()

    private sealed interface Frame {
        data object Open : Frame

        class Text(val text: String) : Frame

        class Closed(val why: String) : Frame
    }

    /** Which orders a socket is about. */
    enum class Watch(val topic: String, val filter: String?) {
        /** What a print station prints; the rest is not its business. */
        ACCEPTED("realtime:print-station", "status=eq.confirmed"),

        /** Every order, as the staff queue on /orders watches them. */
        ALL("realtime:staff-queue", null),
    }

    companion object {
        private val MIN_BACKOFF = 1.seconds
        private val MAX_BACKOFF = 60.seconds
    }
}
