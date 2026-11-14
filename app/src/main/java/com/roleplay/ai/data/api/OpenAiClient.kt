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

    /** Base URLs whose server refused `stream_options`; they are not sent it again. */
    private val noStreamOptions = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Streams the reply text. [onUsage] receives the provider's token counts when it reports
     * them (on the last streamed chunk, or in a non-streamed body).
     */
    fun streamChat(
        config: ApiConfig,
        messages: List<ChatMessage>,
        onUsage: ((Usage) -> Unit)? = null
    ): Flow<String> = callbackFlow {
        val closedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val currentCall = java.util.concurrent.atomic.AtomicReference<Call?>(null)

        fun trySendError(e: IOException) { try { close(e) } catch (_: IllegalStateException) {} }

        fun start(includeUsage: Boolean) {
            val body = ChatRequest(
                model = config.model,
                messages = messages,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                stream = config.stream,
                includeUsage = includeUsage
            ).toJsonElement().toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${config.baseUrl}/chat/completions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Accept", if (config.stream) "text/event-stream" else "application/json")
                .post(body)
                .build()

            val next = client.newCall(request)
            currentCall.set(next)
            if (closedFlag.get()) { next.cancel(); return }
            next.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!closedFlag.get()) { trySendError(e); close() }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val err = resp.body?.string().orEmpty().take(2000)
                            if (includeUsage && resp.code == 400 && "stream_options" in err) {
                                // Some OpenAI-compatible servers reject the option: retry without it.
                                noStreamOptions += config.baseUrl
                                if (!closedFlag.get()) start(includeUsage = false)
                                return
                            }
                            if (!closedFlag.get()) {
                                trySendError(OpenAiException("HTTP ${resp.code}: ${errorMessage(err)}", resp.code))
                                close()
                            }
                            return
                        }
                        val source = resp.body?.source()
                        if (source == null) {
                            if (!closedFlag.get()) { trySendError(OpenAiException("Empty response body")); close() }
                            return
                        }
                        try {
                            if (config.stream) {
                                while (!closedFlag.get() && !source.exhausted()) {
                                    val line = source.readUtf8Line() ?: break
                                    if (!line.startsWith("data:")) continue
                                    val payload = line.removePrefix("data:").trim()
                                    if (payload == "[DONE]") break
                                    val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                                        ?: continue
                                    extractUsage(obj)?.let { usage -> onUsage?.invoke(usage) }
                                    val delta = extractDelta(obj)
                                    if (!delta.isNullOrEmpty()) trySend(delta)
                                }
                            } else {
                                val text = source.readUtf8()
                                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                                obj?.let { extractUsage(it) }?.let { usage -> onUsage?.invoke(usage) }
                                val content = obj?.let { extractDelta(it) }
                                if (!content.isNullOrEmpty()) trySend(content)
                            }
                        } catch (e: IOException) {
                            if (!closedFlag.get()) trySendError(e)
                        } finally {
                            close()
                        }
                    }
                }
            })
        }

        start(includeUsage = config.stream && onUsage != null && config.baseUrl !in noStreamOptions)

        awaitClose {
            closedFlag.set(true)
            currentCall.get()?.cancel()
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
