package de.hamzabistro.printstation.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class OrdersRealtimeTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions =
        SessionManager(SupabaseAuth(test.config, test.client, System::currentTimeMillis), store, System::currentTimeMillis)

    @AfterTest fun close() = test.close()

    /** What the server heard, one message at a time. */
    private val heard = LinkedBlockingQueue<JsonObject>()

    private fun realtimeServer(onJoin: (WebSocket, String) -> Unit) {
        test.server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = json.parseToJsonElement(text).jsonObject
                            heard.add(message)
                            if (message.string("event") == "phx_join") onJoin(webSocket, message.string("ref")!!)
                        }
                    }
                )
                .build()
        )
    }

    /** Signs in first, so the server's answers line up with the socket's requests. */
    private suspend fun signedIn() {
        test.token("access-1", "refresh-1")
        sessions.accessToken()
        test.server.takeRequest()
    }

    @Test
    fun `joins with the account's token and says when an accepted order changed`() = runBlocking<Unit> {
        signedIn()
        realtimeServer { socket, ref ->
            socket.send(
                """{"topic":"${OrdersRealtime.TOPIC}","event":"phx_reply","ref":"$ref",""" +
                    """"payload":{"status":"ok","response":{"postgres_changes":[{"id":1}]}}}"""
            )
            socket.send(
                """{"topic":"${OrdersRealtime.TOPIC}","event":"postgres_changes","ref":null,""" +
                    """"payload":{"data":{"type":"UPDATE","record":{"customer_name":"not read"}},"ids":[1]}}"""
            )
        }
        val realtime =
            OrdersRealtime(test.config, test.client, sessions, PrintLogger, System::currentTimeMillis)

        // Once for the join (whatever happened while it was down), once for the change.
        withTimeout(10.seconds) { assertEquals(2, realtime.changes().take(2).toList().size) }

        val upgrade = test.server.takeRequest()
        assertEquals("/realtime/v1/websocket", upgrade.url.encodedPath)
        assertEquals("anon-key", upgrade.url.queryParameter("apikey"))
        val join = assertNotNull(heard.poll(5, TimeUnit.SECONDS))
        val payload = join["payload"]!!.jsonObject
        assertEquals("access-1", payload.string("access_token"))
        val changes = payload["config"]!!.jsonObject["postgres_changes"]!!.jsonArray.single().jsonObject
        assertEquals("orders", changes.string("table"))
        assertEquals("status=eq.confirmed", changes["filter"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a refused subscription is dropped and tried again`() = runBlocking<Unit> {
        signedIn()
        realtimeServer { socket, ref ->
            socket.send(
                """{"topic":"${OrdersRealtime.TOPIC}","event":"phx_reply","ref":"$ref",""" +
                    """"payload":{"status":"error","response":{"reason":"Unauthorized"}}}"""
            )
        }
        realtimeServer { socket, ref ->
            socket.send(
                """{"topic":"${OrdersRealtime.TOPIC}","event":"phx_reply","ref":"$ref","payload":{"status":"ok","response":{}}}"""
            )
        }
        val realtime =
            OrdersRealtime(test.config, test.client, sessions, PrintLogger, System::currentTimeMillis)

        withTimeout(10.seconds) { realtime.changes().take(1).toList() }
        assertEquals(3, test.server.requestCount)
    }
}

private object PrintLogger : Logger {
    override fun info(message: String) = println("info: $message")

    override fun warn(message: String, error: Throwable?) = println("warn: $message $error")
}
