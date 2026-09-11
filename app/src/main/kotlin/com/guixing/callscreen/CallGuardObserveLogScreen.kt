package com.guixing.callscreen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guixing.observelog.ObserveLogExportAction

/** In-app viewer for [CallGuardObserveLog] - 同 teleprompter 那份查看器一样的结构，"复制全部"
 * 按钮把日志整段塞进剪贴板，方便不方便连 adb 时直接粘贴发过来。 */
@Composable
fun CallGuardObserveLogScreen(onClose: () -> Unit) {
    val entries = CallGuardObserveLog.entries
    val clipboard = LocalClipboardManager.current
    val horizontalScrollState = rememberScrollState()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "诊断日志 (${entries.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(entries.joinToString("\n"))) }) {
                Text("复制")
            }
            ObserveLogExportAction(
                baseName = "电话拦截日志",
                title = "电话拦截 诊断日志",
                lines = { entries.toList() },
            )
            TextButton(onClick = { CallGuardObserveLog.clear() }) {
                Text("清空")
            }
        }
        HorizontalDivider()
        if (entries.isEmpty()) {
            Text(
                "还没有记录——申请角色、或者有来电触发 onScreenCall 之后会显示在这里",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(entries.size) { index ->
                Text(
                    entries[index],
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScrollState)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}
