package com.pocketagent.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.app.PocketAgentApp
import com.pocketagent.app.agent.AgentSession
import com.pocketagent.app.llm.LlmClient
import com.pocketagent.app.llm.OpenAiCompatClient
import com.pocketagent.app.tools.AskUserTool
import com.pocketagent.app.tools.FinishTool
import com.pocketagent.app.tools.GetWeatherTool
import com.pocketagent.app.tools.OpenAppTool
import com.pocketagent.app.tools.SetAlarmTool
import com.pocketagent.app.tools.SetVolumeTool
import com.pocketagent.app.tools.ToolRegistry
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = (app as PocketAgentApp).settings

    private val registry = ToolRegistry().apply {
        register(SetVolumeTool())
        register(SetAlarmTool())
        register(OpenAppTool())
        register(GetWeatherTool())
        register(AskUserTool())
        register(FinishTool())
    }

    val session = AgentSession(
        scope = viewModelScope,
        clientProvider = ::buildClient,
        tools = registry,
        systemPrompt = ::buildSystemPrompt,
    ).apply {
        appContextProvider = { app }
    }

    val messages = session.messages

    private fun buildClient(): LlmClient {
        val cfg = settings.getLlmConfig()
        if (!cfg.isReady) {
            throw IllegalStateException("尚未配置 LLM,请点右上角设置,填写 base_url / api_key / model")
        }
        return OpenAiCompatClient(cfg.baseUrl, cfg.apiKey, cfg.model)
    }

    private fun buildSystemPrompt(): String {
        val now = LocalDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[now.dayOfWeek.value - 1]
        return """
            你是 PocketAgent,一个运行在用户安卓手机上的私人助手,可以通过工具操作这台手机。
            当前时间:$time 星期$weekday。

            规则:
            1. 涉及手机操作(音量、闹钟、打开应用、天气)时,优先调用工具,不要凭空回答。
            2. 工具返回的结果就是事实,不要编造执行结果;工具失败时如实告诉用户。
            3. 设置闹钟时,把用户说的相对时间("明早7点")换算成 24 小时制的绝对时间。
            4. 信息不足或需要确认时,用 ask_user 提问,问题要具体、可直接回答。
            5. 任务完成或确定无法完成时,调用 finish,用 result 向用户汇报。
            6. 回复用简体中文,简洁直接,不要长篇解释。
        """.trimIndent()
    }

    fun send(text: String) = session.send(text)
    fun cancel() = session.cancel()
    fun clearHistory() = session.clearHistory()
}
