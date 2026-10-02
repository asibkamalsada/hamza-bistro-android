package de.hamzabistro.printstation.core

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * A Supabase stand-in over real TLS — [SupabaseConfig] takes nothing but
 * https — and a client that trusts it and nothing else.
 */
class TestServer : AutoCloseable {
    private val certificate =
        HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
            .build()

    val server = MockWebServer()

    val client: OkHttpClient

    init {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        server.start()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
    }

    val config: SupabaseConfig by lazy { SupabaseConfig(server.url("/").toString(), "anon-key") }

    fun reply(code: Int, body: String = "", contentType: String = "application/json") {
        server.enqueue(MockResponse.Builder().code(code).setHeader("Content-Type", contentType).body(body).build())
    }

    fun token(access: String, refresh: String, expiresIn: Long = 3600) = reply(200, tokenBody(access, refresh, expiresIn))

    /**
     * Answers by path rather than in turn, for requests sent side by side:
     * the session's refresh, then [answers] by path. Anything else is a 404,
     * so a request nobody expected fails its test.
     */
    fun routes(answers: Map<String, String>) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    val body = if (path == "/auth/v1/token") tokenBody("access-1", "refresh-1") else answers[path]
                    return if (body == null) MockResponse.Builder().code(404).build()
                    else MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()
                }
            }
    }

    private fun tokenBody(access: String, refresh: String, expiresIn: Long = 3600) =
        """{"access_token":"$access","token_type":"bearer","expires_in":$expiresIn,""" +
            """"refresh_token":"$refresh","user":{"id":"user-1","email":"drucker@example.com"}}"""

    /** Every request the server saw, in the order it saw them. */
    fun requests(): List<RecordedRequest> = generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }.toList()

    override fun close() = server.close()
}

/** Keeps the session in a field, as the Keystore does on the device. */
class MemorySessionStore(var stored: StoredSession? = null) : SessionStore {
    override fun load() = stored

    override fun save(session: StoredSession) {
        stored = session
    }

    override fun clear() {
        stored = null
    }
}
