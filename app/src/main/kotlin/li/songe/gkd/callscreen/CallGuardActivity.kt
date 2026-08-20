package li.songe.gkd.callscreen

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import li.songe.gkd.ui.style.AppTheme
import li.songe.gkd.util.toast

/** 雏形阶段入口 (2026-08-16)。已接入启动页第五张卡片。角色申请 + 规则管理 + 诊断日志三个入口都
 * 在这一个屏幕，靠 [showRules]/[showLog] 做最简单的整屏切换，不引入导航库。 */
class CallGuardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                CallGuardScreen(onBack = { finish() })
            }
        }
    }
}

private fun isRoleHeld(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
    return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallGuardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var roleHeld by remember { mutableStateOf(isRoleHeld(context)) }
    // adb 不方便连接时的排查手段 - 应用内查看 CallGuardObserveLog 的实时诊断日志（同提词器那份
    // "查看诊断日志"一样的模式），角色申请结果和 onScreenCall 的每次来电都会记在这里。
    var showLog by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }

    val requestRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        roleHeld = isRoleHeld(context)
        CallGuardObserveLog.log("role request result, resultCode=${result.resultCode}, roleHeld=$roleHeld")
        toast(if (roleHeld) "已授权来电屏蔽角色" else "未授权，来电拦截无法生效")
    }

    // 真机复现的真 bug (2026-08-16): showLog/showRules 只是同一个 Activity 里的整屏切换，不是
    // 真的导航栈条目 - 系统返回键/手势默认不知道这回事，之前没接 BackHandler 时，在规则管理页按
    // 系统返回会直接把整个 Activity 关掉（表现为"退出电话拦截功能，回到启动页"），不是先退回上一屏。
    // 只在子屏幕打开时启用，回到主屏幕后交还给系统默认行为（走 onBack -> finish()）。
    BackHandler(enabled = showLog || showRules) {
        showLog = false
        showRules = false
    }

    if (showLog) {
        CallGuardObserveLogScreen(onClose = { showLog = false })
        return
    }
    if (showRules) {
        CallGuardRulesScreen(onClose = { showRules = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电话拦截（雏形，验证阶段）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                        "当前系统版本过低（来电屏蔽角色需要 Android 10+），这台设备用不了这个功能"
                    roleHeld ->
                        "已持有「来电屏蔽」角色，黑白名单规则已经在生效——去「规则管理」设置规则，" +
                            "来电会按规则自动拒接或放行。"
                    else ->
                        "还没有申请「来电屏蔽」角色 - 系统不会把来电转发给这个 app，规则不会生效。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Button(
                    onClick = {
                        val roleManager = context.getSystemService(RoleManager::class.java)
                        if (roleManager == null) {
                            toast("获取不到 RoleManager")
                            return@Button
                        }
                        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                            toast("此设备不支持来电屏蔽角色")
                            return@Button
                        }
                        requestRoleLauncher.launch(
                            roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                        )
                    },
                ) {
                    Text(if (roleHeld) "重新申请来电屏蔽角色" else "申请来电屏蔽角色")
                }
            }

            Button(onClick = { showRules = true }) {
                Text("规则管理")
            }

            Button(onClick = { showLog = true }) {
                Text("查看诊断日志 (${CallGuardObserveLog.entries.size})")
            }
        }
    }
}
