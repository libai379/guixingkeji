/*
 * 电话拦截的规则管理页：切换黑/白名单模式 + 增删当前模式下的号码前缀。
 *
 * 关键设计：黑名单和白名单**各自维护独立的前缀列表**（`blacklistPrefixes`/`whitelistPrefixes`，
 * 见 CallGuardStore.kt），不是共用一份再打标签。这样切换模式不会清空或污染对方的规则——是刻意
 * 避开"玻璃预设列表不按场景隔离导致跨屏串扰"那个已经踩过的坑。
 *
 * 号码匹配走前缀而不是全等：座机区号、号段这类场景都需要前缀匹配。标准化逻辑（去空格/横杠/
 * +86 前缀）在 CallGuardStore.kt 里，不假设一定是 11 位手机号。
 */
package li.songe.gkd.callscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 雏形阶段规则管理页 (2026-08-16) - 从简：一个列表，增/删一条前缀规则，不做拦截历史展示。
 * 存储直接读写 [callGuardStoreFlow]（DataStore 机制，跟项目其它模块一致，没有另起一套）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallGuardRulesScreen(onClose: () -> Unit) {
    val store by callGuardStoreFlow.collectAsState()
    var newPrefix by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("黑白名单规则") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CallGuardMode.entries.forEach { mode ->
                    FilterChip(
                        selected = store.mode == mode,
                        onClick = { updateCallGuardStore { it.copy(mode = mode) } },
                        label = { Text(if (mode == CallGuardMode.Blacklist) "黑名单模式" else "白名单模式") },
                    )
                }
            }
            Text(
                if (store.mode == CallGuardMode.Blacklist) {
                    "命中下面前缀的号码会被拒接，其余正常响铃。"
                } else {
                    "只有命中下面前缀的号码会正常响铃，其余全部拒接。" +
                        if (store.activePrefixes.isEmpty()) "当前规则为空 = 会拒接所有非通讯录来电，添加规则前请谨慎。" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPrefix,
                    onValueChange = { newPrefix = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("号码前缀，比如 10086 或 0531") },
                )
                Button(
                    onClick = {
                        val prefix = newPrefix.trim()
                        if (prefix.isNotEmpty()) {
                            updateCallGuardStore { s ->
                                if (prefix in s.activePrefixes) s else s.withActivePrefixes { it + prefix }
                            }
                            newPrefix = ""
                        }
                    },
                ) { Text("添加") }
            }
            Text(
                "当前生效：${if (store.mode == CallGuardMode.Blacklist) "黑名单" else "白名单"}模式，" +
                    "${store.activePrefixes.size} 条规则",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )

            val prefixes = store.activePrefixes
            if (prefixes.isEmpty()) {
                Text(
                    "还没有规则",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn {
                    items(prefixes, key = { it }) { prefix ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(prefix, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    updateCallGuardStore { s -> s.withActivePrefixes { it - prefix } }
                                },
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除「$prefix」")
                            }
                        }
                    }
                }
            }
        }
    }
}
