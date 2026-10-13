package com.pocketagent.app.tools

import android.content.Context
import kotlinx.serialization.json.JsonObject

/** 工具执行上下文,由 AgentSession 提供。 */
interface ToolContext {
    val appContext: Context

    /**
     * 向用户提问并挂起等待回复。
     * 实现方负责把问题展示到 UI,并把用户的下一条输入作为答案返回。
     */
    suspend fun askUser(question: String): String
}

/** 工具统一接口,LLM 通过函数调用使用。 */
interface Tool {
    val name: String
    val description: String

    /** OpenAI function calling 的 parameters JSON Schema。 */
    val parametersSchema: JsonObject

    /** 执行工具,返回给 LLM 看的结果文本。 */
    suspend fun execute(args: JsonObject, context: ToolContext): String
}
