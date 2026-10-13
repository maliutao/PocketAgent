package com.pocketagent.app.device.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 任务执行时的悬浮窗:显示当前步骤 + 停止按钮。
 * Agent 在前台操作其他 APP 时,用户靠它随时中断(需要悬浮窗权限)。
 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: LinearLayout? = null
    private var stepText: TextView? = null

    fun isAvailable(): Boolean = android.provider.Settings.canDrawOverlays(context)

    @SuppressLint("SetTextI18n")
    fun show(text: String, onStop: () -> Unit) {
        if (!isAvailable() || rootView != null) return
        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        }

        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            setText(text)
        }
        val btn = Button(context).apply {
            setText("停止")
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C0392B"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(12), dp(2), dp(12), dp(2))
            minHeight = 0
            minWidth = 0
            setOnClickListener { onStop() }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6222222"))
                cornerRadius = dp(18).toFloat()
            }
            addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(10)
            })
            addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }

        try {
            wm.addView(root, params)
            rootView = root
            stepText = tv
        } catch (e: Exception) {
            android.util.Log.w("PocketAgent", "悬浮窗显示失败: ${e.message}")
        }
    }

    fun update(text: String) {
        stepText?.post { stepText?.text = text }
    }

    fun hide() {
        rootView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // 忽略:窗口可能已移除
            }
        }
        rootView = null
        stepText = null
    }
}
