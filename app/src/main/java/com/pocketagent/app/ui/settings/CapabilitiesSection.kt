package com.pocketagent.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketagent.app.device.a11y.AgentAccessibilityService

/**
 * 设备能力与权限引导区:无障碍 / 悬浮窗 / 电池优化 / 通知。
 * 从系统设置页返回时自动刷新状态。
 */
@Composable
fun CapabilitiesSection() {
    val context = LocalContext.current
    // 从系统设置返回时刷新各权限状态
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val a11yOn = AgentAccessibilityService.isRunning
    val overlayOn = Settings.canDrawOverlays(context)
    val batteryOn = remember(refresh) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    val notifOn = remember(refresh) {
        Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    Text(
        "设备能力(自动化任务需要)",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    )

    CapabilityRow(
        name = "无障碍服务",
        ok = a11yOn,
        okText = "已开启",
        actionText = "去开启",
        onAction = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        hint = "读屏与点击的基础,必开",
    )
    CapabilityRow(
        name = "悬浮窗权限",
        ok = overlayOn,
        okText = "已允许",
        actionText = "去允许",
        onAction = {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        },
        hint = "任务执行时显示步骤和停止按钮",
    )
    CapabilityRow(
        name = "电池优化白名单",
        ok = batteryOn,
        okText = "已忽略",
        actionText = "去忽略",
        onAction = {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            }
        },
        hint = "防止系统冻结后台进程(ColorOS 等必开)",
    )
    if (Build.VERSION.SDK_INT >= 33) {
        CapabilityRow(
            name = "通知权限",
            ok = notifOn,
            okText = "已允许",
            actionText = "去允许",
            onAction = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            hint = "前台服务常驻通知",
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "提示:ColorOS 还建议在 最近任务 里下拉锁定 PocketAgent 卡片,并在 设置→电池 中允许后台运行,可显著降低被系统冻结的概率。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CapabilityRow(
    name: String,
    ok: Boolean,
    okText: String,
    actionText: String,
    onAction: () -> Unit,
    hint: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (ok) "✓ $okText · $hint" else hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!ok) {
            OutlinedButton(onClick = onAction) { Text(actionText) }
        }
    }
}
