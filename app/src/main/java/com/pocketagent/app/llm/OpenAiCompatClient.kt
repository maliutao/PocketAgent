package com.pocketagent.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI 兼容协议实现,适用于 DeepSeek / GLM / Qwen / Kimi / OpenAI 等。
 * baseUrl 形如 https://api.deepseek.com/v1,自动拼接 /chat/completions。
 */
class OpenAiCompatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmClient {

    companion object {
        private const val TAG = "PocketAgent"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolDefinition>?): LlmResponse =
        withContext(Dispatchers.IO) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val url = buildString {
                append(baseUrl.trimEnd('/'))
                append("/chat/completions")
            }
            android.util.Log.d(TAG, "LLM 请求 -> $url (model=$model, ${messages.size} 条消息, ${tools?.size ?: 0} 个工具)")
            val body = buildJsonObject {
                put("model", model)
                put("messages", messages.toJsonArray())
                if (!tools.isNullOrEmpty()) {
                    put("tools", buildJsonArray {
                        tools.forEach { t ->
                            add(buildJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("parameters", t.parameters)
                                }
                            })
                        }
                    })
                }
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val responseText = http.newCall(request).await().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    android.util.Log.e(TAG, "LLM HTTP ${resp.code}: ${text.take(500)}")
                    throw IOException("LLM HTTP ${resp.code}: ${text.take(500)}")
                }
                text
            }

            val parsed = json.decodeFromString(ApiResponse.serializer(), responseText)
            android.util.Log.d(
                TAG,
                "LLM 响应 <- ${android.os.SystemClock.elapsedRealtime() - startedAt}ms, " +
                    "文本=${parsed.choices.firstOrNull()?.message?.content?.take(100)?.replace('\n', ' ')} " +
                    "工具调用=${parsed.choices.firstOrNull()?.message?.toolCalls?.size ?: 0} 个"
            )
            val message = parsed.choices.firstOrNull()?.message
                ?: throw IOException("LLM 响应缺少 choices.message: ${responseText.take(300)}")
            LlmResponse(
                content = message.content,
                toolCalls = message.toolCalls.orEmpty().map {
                    ToolCall(
                        id = it.id,
                        name = it.function.name,
                        arguments = it.function.arguments,
                    )
                },
                promptTokens = parsed.usage?.promptTokens,
                completionTokens = parsed.usage?.completionTokens,
            )
        }

    // ---- 请求 DTO ----

    private fun List<LlmMessage>.toJsonArray() = buildJsonArray {
        forEach { m ->
            add(buildJsonObject {
                put("role", m.role)
                if (m.content != null) put("content", m.content)
                if (m.toolCalls.isNotEmpty()) {
                    put("tool_calls", buildJsonArray {
                        m.toolCalls.forEach { c ->
                            add(buildJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", c.name)
                                    put("arguments", c.arguments)
                                }
                            })
                        }
                    })
                }
                if (m.toolCallId != null) put("tool_call_id", m.toolCallId)
                if (m.name != null) put("name", m.name)
            })
        }
    }

    @Serializable
    private data class ApiResponse(
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null,
    ) {
        @Serializable
        data class Choice(val message: ApiMessage? = null)

        @Serializable
        data class Usage(
            @SerialName("prompt_tokens") val promptTokens: Int? = null,
            @SerialName("completion_tokens") val completionTokens: Int? = null,
        )
    }

    @Serializable
    private data class ApiMessage(
        val content: String? = null,
        val toolCalls: List<ApiToolCall>? = null,
    ) {
        @Serializable
        data class ApiToolCall(
            val id: String,
            val function: ApiFunction,
        )

        @Serializable
        data class ApiFunction(
            val name: String,
            val arguments: String = "{}",
        )
    }
}

/** 挂起等待 OkHttp 响应。 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
