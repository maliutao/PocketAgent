package com.pocketagent.app.tools

import com.pocketagent.app.device.a11y.AgentAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 无障碍服务不可用时的统一提示。 */
private fun a11yUnavailable(): String =
    "错误: 无障碍服务未开启,无法操作屏幕。请提示用户在 设置 → 无障碍 中开启 PocketAgent"

/** 动作类工具的统一结果表述(动作已内置等待,模型无需再调 wait_for_change)。 */
private fun actMsg(actionDesc: String, r: AgentAccessibilityService.ActResult): String = when {
    !r.ok -> "错误: $actionDesc 失败(节点/坐标可能已失效,建议重新 get_screen)"
    r.changed -> "$actionDesc,页面已变化(要看新页面内容请 get_screen)"
    else -> "$actionDesc,但页面未变化(可能无效果,不要原样重复该动作)"
}

// ---------- 读屏 ----------

class GetScreenTool : Tool {
    override val name = "get_screen"
    override val description =
        "读取当前屏幕的压缩控件树。每个节点格式:[序号] 类名 \"文字\" (可点击/可输入等) @x,y。" +
            "序号供 click/long_click/input_text 的 id 参数使用;@x,y 是屏幕像素坐标,供 click_xy/swipe 使用。" +
            "注意:每次调用后序号会重新生成,执行过任何动作后旧序号作废。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        val snap = svc.snapshot() ?: return "错误: 读不到屏幕内容(可能处于锁屏或桌面)"
        return "屏幕指纹: ${snap.fingerprint}\n${snap.treeText}".take(8000)
    }
}

// ---------- 点击 ----------

class ClickTool : Tool {
    override val name = "click"
    override val description =
        "点击控件树中某个节点,参数 id 是最近一次 get_screen 输出里的序号。" +
            "点击后自动等待并报告页面是否变化,通常无需再调 wait_for_change。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") {
                put("type", "integer")
                put("description", "get_screen 输出中的节点序号")
            }
        }
        putJsonArray("required") { add("id") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        val id = args["id"]?.jsonPrimitive?.int ?: return "错误: 缺少 id 参数"
        return actMsg("已点击节点 [$id]", svc.actAndWait { svc.clickNode(id) })
    }
}

class ClickXyTool : Tool {
    override val name = "click_xy"
    override val description = "按屏幕像素坐标点击,坐标参考 get_screen 输出的 @x,y。仅在节点定位失败时兜底使用。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("x") { put("type", "integer") }
            putJsonObject("y") { put("type", "integer") }
        }
        putJsonArray("required") { add("x"); add("y") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        val x = args["x"]?.jsonPrimitive?.double ?: return "错误: 缺少 x"
        val y = args["y"]?.jsonPrimitive?.double ?: return "错误: 缺少 y"
        return actMsg("已点击 ($x,$y)", svc.actAndWait { svc.clickXY(x.toFloat(), y.toFloat()) })
    }
}

class LongClickTool : Tool {
    override val name = "long_click"
    override val description = "长按控件树中某个节点,参数 id 是 get_screen 输出里的序号。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "integer") }
        }
        putJsonArray("required") { add("id") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        val id = args["id"]?.jsonPrimitive?.int ?: return "错误: 缺少 id 参数"
        return actMsg("已长按节点 [$id]", svc.actAndWait { svc.longClickNode(id) })
    }
}

// ---------- 输入 ----------

class InputTextTool : Tool {
    override val name = "input_text"
    override val description = "向可输入节点输入文本。id 是 get_screen 输出里标了(可输入)的节点序号。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "integer") }
            putJsonObject("text") { put("type", "string") }
        }
        putJsonArray("required") { add("id"); add("text") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        val id = args["id"]?.jsonPrimitive?.int ?: return "错误: 缺少 id 参数"
        val text = args["text"]?.jsonPrimitive?.content ?: return "错误: 缺少 text 参数"
        val r = svc.actAndWait { svc.inputText(id, text) }
        return when {
            !r.ok -> "错误: 向节点 [$id] 输入失败(该节点可能不是输入框,或序号已失效,建议重新 get_screen)"
            else -> "已在节点 [$id] 输入 \"$text\"${if (r.changed) ",页面已变化" else ",页面未变化(文本可能已写入)"}"
        }
    }
}

// ---------- 滚动/滑动 ----------

class ScrollTool : Tool {
    override val name = "scroll"
    override val description =
        "滚动当前页面。direction=down 表示看更下方的内容,up 表示回到更上方。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("direction") {
                put("type", "string")
                putJsonArray("enum") { add("up"); add("down") }
            }
        }
        putJsonArray("required") { add("direction") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        val dir = args["direction"]?.jsonPrimitive?.content ?: "down"
        return actMsg("已向 $dir 滚动一屏", svc.actAndWait { svc.scroll(dir) })
    }
}

class SwipeTool : Tool {
    override val name = "swipe"
    override val description = "按屏幕像素坐标滑动手势,坐标参考 get_screen 输出的 @x,y。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("x1") { put("type", "integer") }
            putJsonObject("y1") { put("type", "integer") }
            putJsonObject("x2") { put("type", "integer") }
            putJsonObject("y2") { put("type", "integer") }
        }
        putJsonArray("required") { add("x1"); add("y1"); add("x2"); add("y2") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        val x1 = args["x1"]?.jsonPrimitive?.double ?: return "错误: 缺少 x1"
        val y1 = args["y1"]?.jsonPrimitive?.double ?: return "错误: 缺少 y1"
        val x2 = args["x2"]?.jsonPrimitive?.double ?: return "错误: 缺少 x2"
        val y2 = args["y2"]?.jsonPrimitive?.double ?: return "错误: 缺少 y2"
        return actMsg(
            "已滑动 ($x1,$y1)→($x2,$y2)",
            svc.actAndWait { svc.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()) }
        )
    }
}

// ---------- 导航 ----------

class BackTool : Tool {
    override val name = "back"
    override val description = "按系统返回键,返回上一页或关闭弹窗。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        return actMsg("已按返回", svc.actAndWait { svc.back() })
    }
}

class HomeTool : Tool {
    override val name = "home"
    override val description = "回到桌面。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        return actMsg("已回到桌面", svc.actAndWait { svc.home() })
    }
}

// ---------- 等待 ----------

class WaitForChangeTool : Tool {
    override val name = "wait_for_change"
    override val description =
        "等待页面发生变化(点击后加载、跳转时使用),代替固定等待。返回 是否发生了变化。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("timeout_ms") {
                put("type", "integer")
                put("description", "最长等待毫秒数,默认 3000")
            }
        }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val svc = AgentAccessibilityService.instance ?: return a11yUnavailable()
        val timeout = args["timeout_ms"]?.jsonPrimitive?.int ?: 3000
        val changed = svc.waitScreenChange(timeout.toLong())
        return if (changed) "页面已变化" else "等待 ${timeout}ms 页面未变化"
    }
}

// ---------- 深链 ----------

class OpenDeeplinkTool : Tool {
    override val name = "open_deeplink"
    override val description =
        "打开 URI/深链,可以是 http(s) 网址、APP 的 deeplink(如 xhsdiscover://)或 content URI。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("uri") { put("type", "string") }
        }
        putJsonArray("required") { add("uri") }
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val uri = args["uri"]?.jsonPrimitive?.content ?: return "错误: 缺少 uri 参数"
        val svc = AgentAccessibilityService.instance
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            // 打开后自动等待目标页面加载(动作前取指纹基线,页面加载完成才报"已变化")
            val r = svc?.actAndWait(2500) {
                try {
                    context.appContext.startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            when {
                r == null -> "已打开 $uri(无障碍服务未开启,无法确认页面加载)"
                !r.ok -> "错误: 无法打开 $uri"
                r.changed -> "已打开 $uri,页面已加载"
                else -> "已打开 $uri,页面尚未变化(可能加载慢,可 get_screen 查看)"
            }
        } catch (e: Exception) {
            "错误: 无法打开 $uri (${e.message})"
        }
    }
}
