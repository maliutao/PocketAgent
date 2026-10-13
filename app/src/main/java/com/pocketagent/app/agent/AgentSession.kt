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
@kotlinx.serialization.Serializable
data class UiMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val toolName: String? = null,
) {
    @kotlinx.serialization.Serializable
    enum class Role { USER, ASSISTANT, TOOL, ERROR, ASKING }
}

/** 持久化快照:UI 消息 + LLM 对话历史。 */
@kotlinx.serialization.Serializable
private data class SessionSnapshot(
    val nextId: Long,
    val uiMessages: List<UiMessage>,
    val llmHistory: List<com.pocketagent.app.llm.LlmMessage>,
)

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
    /** 会话持久化文件,null 表示不持久化(仅内存)。 */
    private val persistFile: java.io.File? = null,
) {
    companion object {
        private const val TAG = "PocketAgent"

        /** 单轮任务内工具调用步数上限(PRD 5)。 */
        const val MAX_TOOL_ROUNDS = 25

        /** 上下文裁剪:最近 N 条消息保留完整,更早的超长 tool 结果替换为省略占位。 */
        private const val HISTORY_KEEP = 16
        private const val TRIM_THRESHOLD = 400
    }

    private var nextId = 0L

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages

    /** 会话内保留的对话历史(LLM 侧)。 */
    private val history = mutableListOf<LlmMessage>()

    /** 本次任务结束后是否停留在当前界面(finish stay=true),不拉回 PocketAgent。 */
    @Volatile
    private var stayAfterTask = false

    private var pendingAsk: CompletableDeferred<String>? = null
    private var runningJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    /** 是否正在 ask_user 等待用户回复(此时输入栏仍应可发送)。 */
    private val _isAsking = MutableStateFlow(false)
    val isAsking: StateFlow<Boolean> = _isAsking

    /** 当前执行到的步骤,悬浮窗展示用(如 "第 3 步 click(12)")。 */
    private val _currentStep = MutableStateFlow<String?>(null)
    val currentStep: StateFlow<String?> = _currentStep

    private fun setRunning(value: Boolean) {
        _isRunning.value = value
    }

    private fun append(role: UiMessage.Role, text: String, toolName: String? = null) {
        _messages.value = _messages.value + UiMessage(nextId++, role, text, toolName)
        save()
    }

    // ---- 持久化:防止进程被 ROM 杀掉/冻结后丢失会话 ----

    private val json = Json { ignoreUnknownKeys = true }

    private fun save() {
        val file = persistFile ?: return
        // history 是主线程持续追加的可变列表,必须先拷贝,否则 IO 线程序列化时
        // 会被并发修改(ConcurrentModificationException,message 为 null)
        val snapshot = SessionSnapshot(nextId, _messages.value, history.toList())
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                file.writeText(json.encodeToString(SessionSnapshot.serializer(), snapshot))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "会话保存失败: ${e.message}", e)
            }
        }
    }

    /** 启动时恢复上次会话。 */
    fun restore() {
        val file = persistFile ?: return
        if (!file.exists()) return
        try {
            val snapshot = json.decodeFromString(SessionSnapshot.serializer(), file.readText())
            nextId = snapshot.nextId
            _messages.value = snapshot.uiMessages
            history.addAll(snapshot.llmHistory)
            android.util.Log.i(TAG, "已恢复会话: ${snapshot.uiMessages.size} 条消息")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "会话恢复失败: ${e.message}")
        }
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
                // 任务结束把应用拉回前台展示结果(Agent 常停留在被操作的 APP 里);
                // 但"打开微信"类任务(finish stay=true)应停留在目标应用
                if (!stayAfterTask) bringSelfToFront()
                stayAfterTask = false
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
        // 给历史一个干净的收尾:被取消的任务往往留下悬空的 user/tool 消息
        // (LLM 请求被打断,没有 assistant 回复),下一条任务会把两个请求连起来理解
        history.add(LlmMessage.assistant("[该任务已被用户手动停止,未完成,不要再尝试继续它]"))
    }

    fun clearHistory() {
        if (isRunning.value) cancel()
        history.clear()
        _messages.value = emptyList()
        save()
    }

    private suspend fun runTask(userText: String) {
        android.util.Log.i(TAG, "任务开始: $userText")
        // 重复动作检测(卡死检测的简化版,PRD 5.2)
        var lastActionKey: String? = null
        var repeatCount = 0
        val toolContext = object : ToolContext {
            override val appContext = appContextRef()
            override suspend fun askUser(question: String): String {
                append(UiMessage.Role.ASKING, question)
                // 提问时 Agent 往往停留在其他 APP,把 PocketAgent 拉回前台让用户看到问题并回答
                _currentStep.value = "等你回答"
                bringSelfToFront()
                val deferred = CompletableDeferred<String>()
                pendingAsk = deferred
                _isAsking.value = true
                try {
                    return deferred.await()
                } finally {
                    pendingAsk = null
                    _isAsking.value = false
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
                    // 重复动作检测:同一动作连续执行视为屏幕未响应
                    val actionKey = "${call.name}|${call.arguments}"
                    repeatCount = if (actionKey == lastActionKey) repeatCount + 1 else 1
                    lastActionKey = actionKey
                    if (repeatCount == 2) {
                        history.add(
                            LlmMessage.user(
                                "[系统提示] 你已连续两次执行完全相同的动作(${call.name}),屏幕可能没有响应。" +
                                    "请改用其他策略(重新 get_screen 观察、换定位方式),或调用 finish 汇报失败原因。"
                            )
                        )
                        append(UiMessage.Role.ERROR, "检测到重复动作,已提示模型换策略")
                    }
                    if (repeatCount >= 3) {
                        append(UiMessage.Role.ERROR, "同一动作连续 3 次无响应,判定卡死,任务终止")
                        return
                    }

                    _currentStep.value = "第 ${round + 1} 步 · ${call.name}"
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
                    if (result.startsWith("TASK_DONE") || result == "TASK_FAILED") {
                        stayAfterTask = result == "TASK_DONE_STAY"
                        // finish 工具的 result 参数已由 LLM 放在调用里,提取展示
                        val finalText = extractFinishResult(call.arguments)
                            ?: response.content
                            ?: if (result == "TASK_DONE") "任务完成" else "任务未能完成"
                        append(UiMessage.Role.ASSISTANT, finalText)
                        finished = true
                    }
                }
                if (finished) return
                // 每轮结束裁剪上下文,控制下一次 LLM 调用的 token 开销
                trimHistory()
            }
            val msg = "已达到步数上限($MAX_TOOL_ROUNDS),任务中止"
            append(UiMessage.Role.ERROR, msg)
            history.add(LlmMessage.assistant(msg))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "任务失败", e)
            append(UiMessage.Role.ERROR, "出错了: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            _currentStep.value = null
        }
    }

    /**
     * 上下文裁剪(PRD agent/context):最近 HISTORY_KEEP 条保留完整,
     * 更早的超长 tool 结果(主要是屏幕快照)替换为占位,控制 token 开销。
     */
    private fun trimHistory() {
        if (history.size <= HISTORY_KEEP) return
        val cutoff = history.size - HISTORY_KEEP
        for (i in 1 until cutoff) { // index 0 恒为 system
            val m = history[i]
            if (m.role == "tool" && (m.content?.length ?: 0) > TRIM_THRESHOLD) {
                history[i] = m.copy(content = "[早期步骤的屏幕/结果详情已省略]")
            }
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

    /**
     * 任务结束后把 PocketAgent 拉回前台展示结果。
     * 与 set_alarm 相同手法:singleTask + REORDER_TO_FRONT,不触发重建。
     */
    private fun bringSelfToFront() {
        try {
            val ctx = appContextRef()
            val intent = android.content.Intent(ctx, com.pocketagent.app.MainActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            ctx.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "拉回前台失败: ${e.message}")
        }
    }
}
