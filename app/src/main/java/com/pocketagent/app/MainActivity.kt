package com.pocketagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pocketagent.app.ui.chat.ChatScreen
import com.pocketagent.app.ui.settings.SettingsScreen
import com.pocketagent.app.ui.theme.PocketAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 前台服务保活,降低 ColorOS 冻结概率(应用在前台时允许启动 FGS)
        AgentForegroundService.start(this)
        setContent {
            PocketAgentTheme {
                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    ChatScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
