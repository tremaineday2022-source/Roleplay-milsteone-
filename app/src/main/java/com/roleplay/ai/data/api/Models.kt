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
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.9,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = false
)

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
                put("content", m.content)
            })
        }
    })
    put("temperature", temperature)
    put("max_tokens", maxTokens)
    put("stream", stream)
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
