package de.hamzabistro.printstation.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal val json = Json { ignoreUnknownKeys = true }

private val JSON_MEDIA = "application/json".toMediaType()

internal fun JsonElement.toRequestBody(): RequestBody = toString().toRequestBody(JSON_MEDIA)

/** A JSON object's string field, or null when it is missing or not a string. */
internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

/** A string field of an error body, whichever of the usual names it goes by. */
internal fun errorField(body: String, vararg names: String): String? {
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    return names.firstNotNullOfOrNull { obj.string(it) }
}
