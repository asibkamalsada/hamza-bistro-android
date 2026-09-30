package de.hamzabistro.printstation.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Which Supabase project the station talks to: the same one as the site.
 *
 * [anonKey] is the publishable key the site ships in its own code
 * (src/environments/environment.ts in hamza-bistro-web). It identifies the
 * project, not a user: what a caller may do is decided by the database's
 * policies for the account that is signed in.
 */
class SupabaseConfig(url: String, val anonKey: String) {
    val url: HttpUrl = url.trimEnd('/').toHttpUrl()

    init {
        // Nothing the station sends may travel in clear, least of all a
        // password or a token.
        require(this.url.isHttps) { "the Supabase URL must be https" }
        require(anonKey.isNotBlank()) { "the Supabase key is missing" }
    }

    internal fun endpoint(path: String): HttpUrl.Builder = url.newBuilder().addPathSegments(path)
}
