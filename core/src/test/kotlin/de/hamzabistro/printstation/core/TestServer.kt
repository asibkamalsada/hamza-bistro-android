package de.hamzabistro.printstation.core

import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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

    fun token(access: String, refresh: String, expiresIn: Long = 3600) =
        reply(
            200,
            """{"access_token":"$access","token_type":"bearer","expires_in":$expiresIn,""" +
                """"refresh_token":"$refresh","user":{"id":"user-1","email":"drucker@example.com"}}""",
        )

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
