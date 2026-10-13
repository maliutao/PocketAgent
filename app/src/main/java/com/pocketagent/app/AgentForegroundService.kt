package com.pocketagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 前台服务:任务运行期间保持进程前台化,防止 ColorOS 等国产 ROM 冻结后台进程
 * (无障碍自动化必须在后台持续操作其他 APP)。
 */
class AgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "agent_foreground"
        private const val NOTIFICATION_ID = 100

        fun start(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, AgentForegroundService::class.java)
                )
            } catch (e: Exception) {
                android.util.Log.w("PocketAgent", "前台服务启动失败: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent 运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "PocketAgent 执行任务时的常驻通知"
                setShowBadge(false)
            }
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PocketAgent")
            .setContentText("Agent 服务运行中,可正常执行自动化任务")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
