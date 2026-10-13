package com.pocketagent.app.llm

import kotlinx.serialization.json.JsonObject

/** LLM 客户端统一接口,M2/M3 之前先只有 OpenAI 兼容实现。 */
interface LlmClient {
    /**
     * 发起一轮对话。
     * @param messages 完整对话历史(含工具调用与结果)
     * @param tools 可用工具的 JSON Schema 定义,null 表示不带工具
     * @return 模型回复:文本内容与工具调用列表(可能都为空)
     */
    suspend fun chat(messages: List<LlmMessage>, tools: List<ToolDefinition>?): LlmResponse
}

/** 工具定义(OpenAI function calling 格式)。 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** 对话消息,足以表达 OpenAI 兼容协议所需的角色。 */
data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
) {
    companion object {
        fun system(text: String) = LlmMessage("system", text)
        fun user(text: String) = LlmMessage("user", text)
        fun assistant(text: String?, toolCalls: List<ToolCall> = emptyList()) =
            LlmMessage("assistant", text, toolCalls)
        fun tool(callId: String, name: String, result: String) =
            LlmMessage("tool", result, toolCallId = callId, name = name)
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    /** 参数 JSON 字符串,可能为空对象。 */
    val arguments: String,
)

data class LlmResponse(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val promptTokens: Int?,
    val completionTokens: Int?,
)
