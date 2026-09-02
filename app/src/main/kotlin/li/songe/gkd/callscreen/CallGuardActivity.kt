/*
 * 电话拦截主界面。2026-08-28 改版：
 *
 * - 原来是「申请角色 / 规则管理 / 查看诊断日志」三个大按钮竖排，规则藏在子屏里。现在**规则直接摊在
 *   主屏上**（启用开关 + 黑白名单模式 + 前缀增删），子屏只剩诊断日志一个。
 * - 「申请来电屏蔽角色」按钮改名「申请权限」，和「日志」一起收进右上角图标位，不再占正文空间。
 * - 视觉改成跟提词器同一套液态玻璃：浮层顶栏 + 玻璃卡片 + LiquidToggle/LiquidCompactButton，
 *   背景复用启动页那份 `launcherBackgroundFlow`（用户自己配的纯色/渐变/图片都能透过来折射），
 *   否则玻璃底下是一块纯色，折射不出任何东西，等于白付渲染成本（LiquidDialog 就踩过这个）。
 *
 * 子屏仍然是布尔状态整屏切换（`showLog`），所以**必须配 `BackHandler`**：否则系统返回键会直接关掉
 * 整个 Activity 退回启动页，而不是退回上一屏。这是真机实测发现过的 bug，且只测应用内返回箭头测不
 * 出来（那是显式绑定的 onClick）。见 docs/PITFALLS.md 同名条目。
 */
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import li.songe.gkd.glassui.GlassBackgroundLayer
import li.songe.gkd.glassui.LiquidCompactButton
import li.songe.gkd.glassui.LiquidCompactIconButton
import li.songe.gkd.glassui.LiquidToggle
import li.songe.gkd.glassui.launcherBackgroundFlow
import li.songe.gkd.glasstuning.GlassTarget
import li.songe.gkd.glasstuning.liveGlassParamsFor
import li.songe.gkd.glasstuning.tunedGlass
import li.songe.gkd.ui.style.AppTheme
import li.songe.gkd.util.toast

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
    return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
        roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}

@Composable
private fun CallGuardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store by callGuardStoreFlow.collectAsState()
    val launcherBg by launcherBackgroundFlow.collectAsState()
    var roleHeld by remember { mutableStateOf(isRoleHeld(context)) }
    var showLog by remember { mutableStateOf(false) }
    var newPrefix by remember { mutableStateOf("") }

    val requestRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        roleHeld = isRoleHeld(context)
        CallGuardObserveLog.log("role request result, roleHeld=$roleHeld")
        toast(if (roleHeld) "已获得权限" else "未授权，来电拦截无法生效")
    }

    BackHandler(enabled = showLog) { showLog = false }

    if (showLog) {
        CallGuardObserveLogScreen(onClose = { showLog = false })
        return
    }

    // 全屏唯一的采样源：顶栏、卡片、按钮全部从它取背景。必须是它们的**兄弟节点**而不是祖先，
    // 祖先捕获后代再被后代采样是循环引用，会让 RenderThread 原生栈溢出崩溃（本项目踩过）。
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {
        GlassBackgroundLayer(launcherBg, backdrop, Modifier.fillMaxSize())

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 顶部留出浮层顶栏的高度，正文从它底下滚过去而不是被它挤下来。
            contentPadding = PaddingValues(top = 120.dp, start = 12.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { StatusCard(roleHeld, backdrop) }
            item { EnableCard(store, backdrop) }
            item { ModeCard(store, backdrop) }
            item { AddPrefixCard(store, newPrefix, { newPrefix = it }, { newPrefix = "" }, backdrop) }

            val prefixes = store.activePrefixes
            if (prefixes.isEmpty()) {
                item {
                    Text(
                        "还没有规则",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    )
                }
            } else {
                items(prefixes, key = { it }) { prefix ->
                    PrefixRow(prefix, backdrop)
                }
            }
        }

        // 浮层顶栏 —— 跟提词器各屏同一份配方（chromaticAberration 关掉：它盖在真实滚动内容上，
        // 开着色散在窄条上会露出彩边）。
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .tunedGlass(
                    backdrop = backdrop,
                    params = liveGlassParamsFor(GlassTarget.Other).copy(chromaticAberration = false),
                    cornerRadius = 20.dp,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidCompactIconButton(onClick = onBack, backdrop = backdrop, sampleBackdrop = false, inactiveTint = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "电话拦截",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            // 「申请权限」收进角上图标位（原来是正文里一个大按钮，占地方且不是常用操作——授权一次
            // 之后基本不用再点）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                LiquidCompactIconButton(
                    onClick = {
                        val roleManager = context.getSystemService(RoleManager::class.java)
                        when {
                            roleManager == null -> toast("获取不到 RoleManager")
                            !roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) ->
                                toast("此设备不支持来电屏蔽角色")
                            else -> requestRoleLauncher.launch(
                                roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                            )
                        }
                    },
                    backdrop = backdrop,
                    sampleBackdrop = false,
                    inactiveTint = Color.Transparent,
                ) {
                    Icon(Icons.Filled.Key, contentDescription = "申请权限")
                }
            }
            LiquidCompactIconButton(
                onClick = { showLog = true },
                backdrop = backdrop,
                sampleBackdrop = false,
                inactiveTint = Color.Transparent,
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "日志")
            }
        }
    }
}

/** 卡片外壳：本屏所有分区共用一份配方，改一处三处一起变。 */
@Composable
private fun GlassCard(backdrop: Backdrop, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .tunedGlass(
                backdrop = backdrop,
                params = liveGlassParamsFor(GlassTarget.Other).copy(chromaticAberration = false),
                cornerRadius = 18.dp,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { content() }
}

@Composable
private fun StatusCard(roleHeld: Boolean, backdrop: Backdrop) {
    GlassCard(backdrop) {
        Text(
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                    "当前系统版本过低（来电屏蔽需要 Android 10+），这台设备用不了这个功能"
                roleHeld -> "已获得权限，下面的规则会在来电时生效"
                else -> "还没有权限——系统不会把来电转发给本应用，规则不会生效。点右上角钥匙图标申请"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EnableCard(store: CallGuardStore, backdrop: Backdrop) {
    GlassCard(backdrop) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("启用来电拦截", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (store.enabled) "已启用，按下面规则拦截" else "已关闭，所有来电正常响铃",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LiquidToggle(
                selected = { store.enabled },
                onSelect = { on -> updateCallGuardStore { it.copy(enabled = on) } },
                backdrop = backdrop,
            )
        }
    }
}

@Composable
private fun ModeCard(store: CallGuardStore, backdrop: Backdrop) {
    GlassCard(backdrop) {
        Column {
            Text("名单模式", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CallGuardMode.entries.forEach { mode ->
                    val selected = store.mode == mode
                    LiquidCompactButton(
                        onClick = { updateCallGuardStore { it.copy(mode = mode) } },
                        backdrop = backdrop,
                        active = selected,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        sampleBackdrop = false,
                    ) {
                        Text(
                            if (mode == CallGuardMode.Blacklist) "黑名单" else "白名单",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            Text(
                if (store.mode == CallGuardMode.Blacklist) {
                    "命中下面前缀的号码会被拒接，其余正常响铃。"
                } else {
                    "只有命中下面前缀的号码会正常响铃，其余全部拒接。" +
                        if (store.activePrefixes.isEmpty()) "当前规则为空 —— 为避免误拦，规则为空时一律放行。" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun AddPrefixCard(
    store: CallGuardStore,
    newPrefix: String,
    onPrefixChange: (String) -> Unit,
    onAdded: () -> Unit,
    backdrop: Backdrop,
) {
    GlassCard(backdrop) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newPrefix,
                onValueChange = onPrefixChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("号码前缀，比如 10086 或 0531") },
            )
            Spacer(Modifier.width(10.dp))
            LiquidCompactButton(
                onClick = {
                    val prefix = newPrefix.trim()
                    if (prefix.isNotEmpty()) {
                        updateCallGuardStore { s ->
                            if (prefix in s.activePrefixes) s else s.withActivePrefixes { it + prefix }
                        }
                        onAdded()
                    }
                },
                backdrop = backdrop,
                active = newPrefix.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                sampleBackdrop = false,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加")
            }
        }
    }
}

@Composable
private fun PrefixRow(prefix: String, backdrop: Backdrop) {
    GlassCard(backdrop) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(prefix, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            LiquidCompactIconButton(
                onClick = { updateCallGuardStore { s -> s.withActivePrefixes { it - prefix } } },
                backdrop = backdrop,
                sampleBackdrop = false,
                inactiveTint = Color.Transparent,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "删除「$prefix」")
            }
        }
    }
}
