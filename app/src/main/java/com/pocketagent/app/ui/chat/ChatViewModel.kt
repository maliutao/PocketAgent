package com.pocketagent.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.app.PocketAgentApp
import com.pocketagent.app.agent.AgentSession
import com.pocketagent.app.device.overlay.OverlayController
import com.pocketagent.app.llm.LlmClient
import com.pocketagent.app.llm.OpenAiCompatClient
import com.pocketagent.app.tools.AskUserTool
import com.pocketagent.app.tools.BackTool
import com.pocketagent.app.tools.ClickTool
import com.pocketagent.app.tools.ClickXyTool
import com.pocketagent.app.tools.FinishTool
import com.pocketagent.app.tools.GetScreenTool
import com.pocketagent.app.tools.GetWeatherTool
import com.pocketagent.app.tools.HomeTool
import com.pocketagent.app.tools.InputTextTool
import com.pocketagent.app.tools.LongClickTool
import com.pocketagent.app.tools.OpenAppTool
import com.pocketagent.app.tools.OpenDeeplinkTool
import com.pocketagent.app.tools.ScrollTool
import com.pocketagent.app.tools.SetAlarmTool
import com.pocketagent.app.tools.SetVolumeTool
import com.pocketagent.app.tools.SwipeTool
import com.pocketagent.app.tools.WaitForChangeTool
import com.pocketagent.app.tools.ToolRegistry
import java.time.LocalDateTime
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = (app as PocketAgentApp).settings

    private val registry = ToolRegistry().apply {
        // 系统工具
        register(SetVolumeTool())
        register(SetAlarmTool())
        register(OpenAppTool())
        register(GetWeatherTool())
        // UI 通用工具(无障碍底座,M2)
        register(GetScreenTool())
        register(ClickTool())
        register(ClickXyTool())
        register(LongClickTool())
        register(InputTextTool())
        register(ScrollTool())
        register(SwipeTool())
        register(BackTool())
        register(HomeTool())
        register(WaitForChangeTool())
        register(OpenDeeplinkTool())
        // 控制类
        register(AskUserTool())
        register(FinishTool())
    }

    val session = AgentSession(
        scope = viewModelScope,
        clientProvider = ::buildClient,
        tools = registry,
        systemPrompt = ::buildSystemPrompt,
        persistFile = java.io.File(app.filesDir, "session.json"),
    ).apply {
        appContextProvider = { app }
        restore()
    }

    val messages = session.messages

    init {
        // 任务执行期间显示悬浮窗(当前步骤 + 停止按钮)
        val overlay = OverlayController(app)
        // 任务执行期间持有唤醒锁:锁屏后任务不因 CPU 休眠而饿死
        // (上限 10 分钟兜底,任务结束即释放,不会常驻耗电)
        val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PocketAgent:task")
        wakeLock.setReferenceCounted(false)
        viewModelScope.launch {
            session.isRunning.collect { running ->
                if (running) {
                    wakeLock.acquire(10 * 60 * 1000L)
                    overlay.show(session.currentStep.value ?: "任务执行中…") { session.cancel() }
                } else {
                    overlay.hide()
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
        viewModelScope.launch {
            session.currentStep.collect { step ->
                overlay.update(step ?: "任务执行中…")
            }
        }
    }

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
            7. 操作手机屏幕(进入某个 APP 的某个页面、查某个信息)时,遵循"先观察后行动":
               先 get_screen 读屏,再决定点哪里;每一步只做一个动作。
               动作类工具(click/scroll/swipe/back 等)已内置等待并会报告页面是否变化,无需再调 wait_for_change;
               只有需要看新页面的具体内容时才 get_screen。
            8. 屏幕信息以 get_screen 的输出为准;找不到目标控件时先 scroll 再找;坐标点击(click_xy)是最后手段。
               ask_user 之后屏幕已回到本应用,旧节点序号全部失效,继续操作前先重新打开目标应用。
            9. 遇到弹窗、广告、升级提示,优先关闭它们再继续任务。
            10. 页面没有变化时不要重复同一动作;连续失败时反思换策略,或调用 finish 汇报卡在哪一步。
        """.trimIndent()
    }

    fun send(text: String) = session.send(text)
    fun cancel() = session.cancel()
    fun clearHistory() = session.clearHistory()
}
