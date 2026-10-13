package com.pocketagent.app.tools

import android.content.Context
import android.media.AudioManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---------- 音量 ----------

class SetVolumeTool : Tool {
    override val name = "set_volume"
    override val description =
        "调节手机音量。level 为 0~100 的百分比,不是绝对档位。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("stream") {
                put("type", "string")
                putJsonArray("enum") {
                    add("music"); add("ring"); add("alarm"); add("notification")
                }
                put("description", "音量流:媒体音量(music)、铃声(ring)、闹钟(alarm)、通知(notification),默认 music")
            }
            putJsonObject("level") {
                put("type", "integer")
                put("description", "目标音量百分比 0~100")
            }
        }
        putJsonArray("required") { add("level") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val am = context.appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (args["stream"]?.jsonPrimitive?.content?.lowercase()) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val percent = args["level"]?.jsonPrimitive?.int ?: return "错误: 缺少 level 参数"
        if (percent !in 0..100) return "错误: level 必须在 0~100 之间"
        val max = am.getStreamMaxVolume(stream)
        val target = (percent * max + 50) / 100
        am.setStreamVolume(stream, target, 0)
        return "已将${streamName(stream)}音量设置为 $percent%(档位 $target/$max)"
    }

    private fun streamName(stream: Int) = when (stream) {
        AudioManager.STREAM_RING -> "铃声"
        AudioManager.STREAM_ALARM -> "闹钟"
        AudioManager.STREAM_NOTIFICATION -> "通知"
        else -> "媒体"
    }
}

// ---------- 闹钟 ----------

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val description =
        "通过系统时钟 APP 设置闹钟。时间参数请换算为 24 小时制的绝对时间,比如用户说\"明早7点\"而现在是 23:00,则 hour=7。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("hour") {
                put("type", "integer")
                put("description", "小时,0~23(24小时制)")
            }
            putJsonObject("minute") {
                put("type", "integer")
                put("description", "分钟,0~59,默认 0")
            }
            putJsonObject("label") {
                put("type", "string")
                put("description", "闹钟标签,可选")
            }
        }
        putJsonArray("required") { add("hour") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val hour = args["hour"]?.jsonPrimitive?.int ?: return "错误: 缺少 hour 参数"
        val minute = args["minute"]?.jsonPrimitive?.int ?: 0
        if (hour !in 0..23 || minute !in 0..59) return "错误: 时间参数超出范围"
        val label = args["label"]?.jsonPrimitive?.content ?: "PocketAgent"

        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.appContext.startActivity(intent)
            // ColorOS 时钟 APP 在前台停留片刻才会把闹钟写入数据库;
            // 抢前台太快会导致闹钟静默丢失(真机验证过)。
            // M1 曾因 delay 被后台冻结而去掉,M2 已有前台服务 + 电池白名单保活,
            // 可以安全等待;若仍被冻结,用户手动打开应用后协程恢复,结果不丢。
            kotlinx.coroutines.delay(1200)
            val back = android.content.Intent(context.appContext, com.pocketagent.app.MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            context.appContext.startActivity(back)
            "已设置闹钟 ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')} ($label)"
        } catch (e: android.content.ActivityNotFoundException) {
            "错误: 设备上没有可用的时钟应用来设置闹钟"
        }
    }
}

// ---------- 打开 APP ----------

class OpenAppTool : Tool {
    override val name = "open_app"
    override val description =
        "打开手机上的应用。参数可以是应用名(如\"小红书\"、\"微信\")或包名(如 com.xingin.xhs)。"

    /** 已安装应用列表缓存:首次全量加载较慢(几百个应用的标签),缓存 10 分钟。 */
    companion object {
        @Volatile
        private var cachedApps: List<Pair<String, String>>? = null // (packageName, label)
        @Volatile
        private var cachedAt: Long = 0
        private const val CACHE_MS = 10 * 60 * 1000L

        fun invalidateCache() {
            cachedApps = null
        }
    }

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "应用名称或包名")
            }
        }
        putJsonArray("required") { add("name") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val query = args["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isEmpty()) return "错误: 缺少 name 参数"

        val pm = context.appContext.packageManager
        // 1) 直接按包名匹配
        pm.getLaunchIntentForPackage(query)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.appContext.startActivity(it)
            return "已打开 $query"
        }
        // 2) 按应用名模糊匹配(包含关系,取最短名优先),结果走 10 分钟缓存
        val now = android.os.SystemClock.elapsedRealtime()
        var apps = cachedApps
        if (apps == null || now - cachedAt > CACHE_MS) {
            apps = pm.getInstalledApplications(0).map { it.packageName to pm.getApplicationLabel(it).toString() }
            cachedApps = apps
            cachedAt = now
        }
        val match = apps
            .filter { (pkg, label) ->
                label.contains(query, ignoreCase = true) || query.contains(label, ignoreCase = true) ||
                    pkg.contains(query, ignoreCase = true)
            }
            .minByOrNull { (_, label) ->
                // 优先级:名称完全相等 > 前缀匹配 > 其余包含;同级取更短名。
                // 否则"设置"会被"语音设置"这类长名抢先(系统应用可能是英文标签)。
                val tier = when {
                    label.equals(query, ignoreCase = true) -> 0
                    label.startsWith(query, ignoreCase = true) -> 1
                    else -> 2
                }
                tier * 1000 + label.length
            }
        return if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.first)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.appContext.startActivity(intent)
                "已打开 ${match.second} (${match.first})"
            } else {
                "错误: 找到应用 ${match.second} 但它没有启动入口"
            }
        } else {
            "错误: 没有找到名为 \"$query\" 的应用"
        }
    }
}

// ---------- 询问用户 ----------

class AskUserTool : Tool {
    override val name = "ask_user"
    override val description =
        "当任务信息不完整、或下一步是敏感操作(支付/发送/删除/授权)需要用户确认时,向用户提问并等待回复。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("question") {
                put("type", "string")
                put("description", "要问用户的问题,要具体、可直接回答")
            }
        }
        putJsonArray("required") { add("question") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val question = args["question"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (question.isEmpty()) return "错误: 缺少 question 参数"
        val answer = context.askUser(question)
        return "用户回复: $answer"
    }
}

// ---------- 结束任务 ----------

class FinishTool : Tool {
    override val name = "finish"
    override val description =
        "任务完成或确定无法继续时,结束任务并给出最终答复。用 result 面向用户汇报结果。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("result") {
                put("type", "string")
                put("description", "给用户看的最终结果汇报")
            }
            putJsonObject("success") {
                put("type", "boolean")
                put("description", "任务是否成功完成")
            }
        }
        putJsonArray("required") { add("result") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val success = args["success"]?.jsonPrimitive?.booleanOrNull ?: true
        return if (success) "TASK_DONE" else "TASK_FAILED"
    }
}
