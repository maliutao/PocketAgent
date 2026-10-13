package com.pocketagent.app.device.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import kotlinx.coroutines.delay

/**
 * 核心能力服务:读取控件树、执行点击/输入/滚动/手势。
 * 工具层(tools/UiTools)通过 companion.instance 访问本服务。
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PocketAgent"

        @Volatile
        private var sInstance: AgentAccessibilityService? = null

        val instance: AgentAccessibilityService? get() = sInstance
        val isRunning: Boolean get() = sInstance != null
    }

    /** 上一次快照的节点表,索引与压缩文本中的 [n] 对应,仅在本步有效。 */
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    data class ScreenSnapshot(
        val packageName: String?,
        val treeText: String,
        val fingerprint: String,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        sInstance = this
        android.util.Log.i(TAG, "无障碍服务已连接")
        // 无障碍服务被系统拉起时应用可能不在前台,FGS 启动失败没关系,try 住
        runCatching { com.pocketagent.app.AgentForegroundService.start(this) }
    }

    override fun onDestroy() {
        sInstance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        sInstance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 快照模式按需拉取,不消费事件
    }

    override fun onInterrupt() {}

    // ---------- 读屏 ----------

    /**
     * 压缩控件树(PRD 4.2):
     * 只保留 可点击/可输入/可滚动/可勾选 或 有文字描述 的可见节点,上限 [maxNodes] 个。
     * 输出格式:[序号] 类名 "文字" (标志) @x,y(屏幕中心像素坐标)
     */
    fun snapshot(maxNodes: Int = 150): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val lines = ArrayList<String>(maxNodes)
        val nodes = ArrayList<AccessibilityNodeInfo>(maxNodes)
        val fp = StringBuilder()

        fun dfs(node: AccessibilityNodeInfo) {
            if (nodes.size >= maxNodes) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = (if (text.isNotEmpty()) text else desc).replace('\n', ' ').take(40)
            val flags = buildString {
                if (node.isClickable) append("可点击,")
                if (node.isEditable) append("可输入,")
                if (node.isScrollable) append("可滚动,")
                if (node.isCheckable) append("可勾选,")
            }.trimEnd(',')
            val interesting = flags.isNotEmpty() || label.isNotEmpty()
            if (node.isVisibleToUser && interesting) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val idx = nodes.size
                nodes.add(node)
                lines.add("[$idx] ${node.className?.toString()?.substringAfterLast('.') ?: "?"} \"$label\"${
                    if (flags.isNotEmpty()) " ($flags)" else ""
                } @${r.centerX()},${r.centerY()}")
                // 指纹只含内容特征,不含序号,结构不变则指纹不变
                fp.append(node.className).append('|').append(label).append('|')
                    .append(flags).append('|').append(r.centerX()).append(',').append(r.centerY()).append('\n')
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(::dfs)
            }
        }
        dfs(root)
        lastNodes = nodes
        val sb = StringBuilder("当前应用: ${root.packageName}\n")
        sb.append(lines.joinToString("\n"))
        return ScreenSnapshot(root.packageName?.toString(), sb.toString(), md5(fp.toString()))
    }

    // ---------- 操作 ----------

    /** 按快照序号点击:优先走无障碍 ACTION_CLICK(找不到可点击祖先则向上找),失败用手势点中心。 */
    fun clickNode(index: Int): Boolean {
        val node = lastNodes.getOrNull(index) ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapCenter(node)
    }

    fun longClickNode(index: Int): Boolean {
        val node = lastNodes.getOrNull(index) ?: return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        return tapCenter(node, durationMs = 700)
    }

    /** 按坐标点击。 */
    fun clickXY(x: Float, y: Float): Boolean = dispatchTap(x, y, 60)

    /** 输入文本:先聚焦再用 ACTION_SET_TEXT。 */
    fun inputText(index: Int, text: String): Boolean {
        val node = lastNodes.getOrNull(index) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * 滚动:direction 为内容移动方向——"down"(看更下方内容)/"up"(看更上方内容)。
     * 优先对可滚动容器执行无障碍滚动,失败退化为手势滑动。
     */
    fun scroll(direction: String): Boolean {
        val forward = direction.equals("down", ignoreCase = true)
        val scrollable = lastNodes.firstOrNull { it.isScrollable }
        if (scrollable != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return true
        }
        val cy = resources.displayMetrics.heightPixels / 2f
        val cx = resources.displayMetrics.widthPixels / 2f
        return if (forward) swipe(cx, cy + 400, cx, cy - 400) else swipe(cx, cy - 400, cx, cy + 400)
    }

    /** 手势滑动,坐标为屏幕像素。 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 挂起等待屏幕指纹变化,返回是否变化(超时 false)。 */
    suspend fun waitScreenChange(timeoutMs: Long): Boolean {
        val before = snapshot()?.fingerprint ?: return false
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            delay(250)
            val now = snapshot()?.fingerprint
            if (now != null && now != before) return true
        }
        return false
    }

    /** 动作执行结果:ok=动作本身是否成功,changed=页面指纹是否变化。 */
    data class ActResult(val ok: Boolean, val changed: Boolean)

    /**
     * 执行一个动作并自动等待页面变化(工具层用,省掉单独的 wait_for_change 步)。
     * 先取动作前指纹做基线(不能动作后再取,点击效果可能已渲染完)。
     */
    suspend fun actAndWait(timeoutMs: Long = 1500, action: () -> Boolean): ActResult {
        val before = snapshot()?.fingerprint
        val ok = action()
        if (!ok) return ActResult(false, false)
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            delay(200)
            val now = snapshot()?.fingerprint
            if (now != null && now != before) return ActResult(true, true)
        }
        return ActResult(true, false)
    }

    // ---------- 内部 ----------

    private fun tapCenter(node: AccessibilityNodeInfo, durationMs: Long = 60): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return dispatchTap(r.exactCenterX(), r.exactCenterY(), durationMs)
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
