package com.roleplay.ai.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiException(message: String, val code: Int? = null) : IOException(message)

class OpenAiClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun streamChat(config: ApiConfig, messages: List<ChatMessage>): Flow<String> = callbackFlow {
        val body = ChatRequest(
            model = config.model,
            messages = messages,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            stream = config.stream
        ).toJsonElement().toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", if (config.stream) "text/event-stream" else "application/json")
            .post(body)
            .build()

        val call = client.newCall(request)
        var closed = false

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!closed) { trySendError(e); close() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty().take(2000)
                        if (!closed) {
                            trySendError(OpenAiException("HTTP ${resp.code}: ${errorMessage(err)}", resp.code))
                            close()
                        }
                        return
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        if (!closed) { trySendError(OpenAiException("Empty response body")); close() }
                        return
                    }
                    try {
                        if (config.stream) {
                            while (!closed && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (!line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload == "[DONE]") break
                                val delta = runCatching {
                                    extractDelta(json.parseToJsonElement(payload).jsonObject)
                                }.getOrNull()
                                if (!delta.isNullOrEmpty()) trySend(delta)
                            }
                        } else {
                            val text = source.readUtf8()
                            val content = runCatching {
                                extractDelta(json.parseToJsonElement(text).jsonObject)
                            }.getOrNull()
                            if (!content.isNullOrEmpty()) trySend(content)
                        }
                    } catch (e: IOException) {
                        if (!closed) trySendError(e)
                    } finally {
                        close()
                    }
                }
            }

            private fun trySendError(e: IOException) { try { close(e) } catch (_: IllegalStateException) {} }
        })

        awaitClose {
            closed = true
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun complete(config: ApiConfig, messages: List<ChatMessage>): String {
        val builder = StringBuilder()
        streamChat(config.copy(stream = false), messages).collect { builder.append(it) }
        return builder.toString()
    }

    private fun errorMessage(raw: String): String = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        (obj["error"] as? JsonObject)?.get("message")?.let { (it as JsonPrimitive).content } ?: raw.take(300)
    }.getOrDefault(raw.take(300))
}
