package com.roleplay.ai.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /** Image URLs (normally `data:` URLs) sent as `image_url` content parts to vision models. */
    val images: List<String> = emptyList()
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.9,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = false,
    /** Ask a streaming endpoint to append token usage to its last chunk. */
    val includeUsage: Boolean = false
)

/** Token counts reported by the provider. */
data class Usage(val promptTokens: Int, val completionTokens: Int?)

data class ApiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val stream: Boolean
)

internal fun ChatRequest.toJsonElement(): JsonElement = buildJsonObject {
    put("model", model)
    put("messages", buildJsonArray {
        messages.forEach { m ->
            add(buildJsonObject {
                put("role", m.role)
                if (m.images.isEmpty()) {
                    put("content", m.content)
                } else {
                    put("content", buildJsonArray {
                        if (m.content.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            })
                        }
                        m.images.forEach { url ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", url) })
                            })
                        }
                    })
                }
            })
        }
    })
    put("temperature", temperature)
    put("max_tokens", maxTokens)
    put("stream", stream)
    if (stream && includeUsage) {
        put("stream_options", buildJsonObject { put("include_usage", true) })
    }
}

/** The `usage` object of a response or of the final streamed chunk, if there is one. */
internal fun extractUsage(obj: JsonObject): Usage? {
    val usage = obj["usage"] as? JsonObject ?: return null
    val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
    val completion = (usage["completion_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
    return Usage(prompt, completion)
}

internal fun extractDelta(obj: JsonObject): String? {
    val choices = obj["choices"] as? JsonArray ?: return null
    val first = choices.firstOrNull() as? JsonObject ?: return null
    val delta = first["delta"] as? JsonObject
    if (delta != null) {
        val content = delta["content"] as? JsonPrimitive
        if (content?.isString == true) return content.content
    }
    val message = first["message"] as? JsonObject
    if (message != null) {
        val content = message["content"] as? JsonPrimitive
        if (content?.isString == true) return content.content
    }
    return null
}
