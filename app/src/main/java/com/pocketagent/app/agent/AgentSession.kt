package com.pocketagent.app.agent

import com.pocketagent.app.llm.LlmClient
import com.pocketagent.app.llm.LlmMessage
import com.pocketagent.app.tools.ToolContext
import com.pocketagent.app.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** UI 展示用的一条消息。 */
data class UiMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val toolName: String? = null,
) {
    enum class Role { USER, ASSISTANT, TOOL, ERROR, ASKING }
}

/**
 * M1 版 Agent 循环:对话 + 工具调用。
 * 用户发一条指令 → LLM 决定调哪些工具 → 执行 → 结果回填 → 直到 LLM 给出最终答复或调用 finish。
 * ask_user 会挂起等待用户下一条输入(该输入作为答案,而不是新任务)。
 */
class AgentSession(
    private val scope: CoroutineScope,
    private val clientProvider: () -> LlmClient,
    private val tools: ToolRegistry,
    /** 每轮任务开始时生成系统提示词(内含当前时间等动态信息)。 */
    private val systemPrompt: () -> String = { "" },
) {
    companion object {
        private const val TAG = "PocketAgent"

        /** 单轮任务内工具调用轮数上限。 */
        const val MAX_TOOL_ROUNDS = 8
    }

    private var nextId = 0L

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages

    /** 会话内保留的对话历史(LLM 侧)。 */
    private val history = mutableListOf<LlmMessage>()

    private var pendingAsk: CompletableDeferred<String>? = null
    private var runningJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private fun setRunning(value: Boolean) {
        _isRunning.value = value
    }

    private fun append(role: UiMessage.Role, text: String, toolName: String? = null) {
        _messages.value = _messages.value + UiMessage(nextId++, role, text, toolName)
    }

    /** 用户点击发送。若当前正在 ask_user 等待,该输入作为答案。 */
    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty()) return
        val pending = pendingAsk
        if (pending != null) {
            append(UiMessage.Role.USER, text)
            pending.complete(text)
            return
        }
        if (isRunning.value) {
            append(UiMessage.Role.ERROR, "上一条任务还在执行中,请稍候")
            return
        }
        append(UiMessage.Role.USER, text)
        runningJob = scope.launch {
            setRunning(true)
            try {
                runTask(text)
            } finally {
                setRunning(false)
            }
        }
    }

    /** 立即取消当前任务。 */
    fun cancel() {
        pendingAsk?.complete("__已取消__")
        runningJob?.cancel()
        runningJob = null
        setRunning(false)
        append(UiMessage.Role.ERROR, "已停止当前任务")
    }

    fun clearHistory() {
        if (isRunning.value) cancel()
        history.clear()
        _messages.value = emptyList()
    }

    private suspend fun runTask(userText: String) {
        android.util.Log.i(TAG, "任务开始: $userText")
        val toolContext = object : ToolContext {
            override val appContext = appContextRef()
            override suspend fun askUser(question: String): String {
                append(UiMessage.Role.ASKING, question)
                val deferred = CompletableDeferred<String>()
                pendingAsk = deferred
                try {
                    return deferred.await()
                } finally {
                    pendingAsk = null
                }
            }
        }

        // 刷新 index 0 的系统提示词(带当前时间)
        val sys = LlmMessage.system(systemPrompt())
        if (history.isNotEmpty() && history.first().role == "system") {
            history[0] = sys
        } else {
            history.add(0, sys)
        }
        history.add(LlmMessage.user(userText))
        try {
            val client = clientProvider()
            for (round in 0 until MAX_TOOL_ROUNDS) {
                val response = client.chat(history.toList(), tools.toDefinitions())
                history.add(LlmMessage.assistant(response.content, response.toolCalls))

                if (response.toolCalls.isEmpty()) {
                    // 纯文本回复,任务自然结束
                    android.util.Log.i(TAG, "任务完成(纯文本回复)")
                    response.content?.let { append(UiMessage.Role.ASSISTANT, it) }
                    return
                }

                var finished = false
                for (call in response.toolCalls) {
                    val tool = tools.get(call.name)
                    val result = if (tool == null) {
                        "错误: 未知工具 ${call.name}"
                    } else {
                        android.util.Log.i(TAG, "工具调用: ${call.name}(${call.arguments})")
                        append(UiMessage.Role.TOOL, "调用 ${call.name}(${call.arguments}) …", call.name)
                        runTool(tool, call.arguments, toolContext).also {
                            android.util.Log.i(TAG, "工具结果: ${call.name} -> ${it.take(200)}")
                        }
                    }
                    history.add(LlmMessage.tool(call.id, call.name, result))
                    if (result == "TASK_DONE" || result == "TASK_FAILED") {
                        // finish 工具的 result 参数已由 LLM 放在调用里,提取展示
                        val finalText = extractFinishResult(call.arguments)
                            ?: response.content
                            ?: if (result == "TASK_DONE") "任务完成" else "任务未能完成"
                        append(UiMessage.Role.ASSISTANT, finalText)
                        finished = true
                    }
                }
                if (finished) return
            }
            val msg = "已达到工具调用轮数上限($MAX_TOOL_ROUNDS),任务中止"
            append(UiMessage.Role.ERROR, msg)
            history.add(LlmMessage.assistant(msg))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "任务失败", e)
            append(UiMessage.Role.ERROR, "出错了: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun runTool(
        tool: com.pocketagent.app.tools.Tool,
        arguments: String,
        context: ToolContext,
    ): String = try {
        val args: JsonObject = if (arguments.isBlank()) {
            kotlinx.serialization.json.buildJsonObject { }
        } else {
            Json.parseToJsonElement(arguments).let {
                it as? JsonObject ?: kotlinx.serialization.json.buildJsonObject { }
            }
        }
        // 工具统一在 IO 线程执行(如遍历应用列表),避免阻塞主线程导致界面冻结
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tool.execute(args, context)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        "错误: 工具执行失败 - ${e.message ?: e.javaClass.simpleName}"
    }

    private fun extractFinishResult(arguments: String): String? = try {
        val obj = Json.parseToJsonElement(arguments) as? JsonObject ?: return null
        (obj["result"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    } catch (e: Exception) {
        null
    }

    /** 由 ViewModel 注入 Application context。 */
    var appContextProvider: () -> android.content.Context = {
        throw IllegalStateException("appContextProvider 未注入")
    }

    private fun appContextRef() = appContextProvider()
}
