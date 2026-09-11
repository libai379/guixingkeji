/*
 * 定位修改的应用内日志查看器 —— 2026-09-05。跟提词器/电话拦截那两份结构一致（返回、条数、复制、
 * 导出、清空 + 等宽单行列表），差别只在它是**盖在 WebView 上的一层 ComposeView**，不是一个独立
 * Activity：加 Activity 就要改 AndroidManifest，而清单在 sync-to-public.sh 的 NEVER_COPY 里，
 * 两个工程得各改一遍、还很容易只改一边。盖一层的写法两边靠同步脚本自动一致。
 */
package com.guixing.observelog

import androidx.compose.foundation.background
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

@Composable
fun WebToolObserveLogScreen(onClose: () -> Unit) {
    val entries = WebToolObserveLog.entries
    val clipboard = LocalClipboardManager.current
    val horizontalScrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            // 不透明底色是必须的：这一层直接盖在 WebView 上，不上底色的话网页内容会透过来，
            // 跟日志文字叠在一起完全没法看。
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
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
                baseName = "定位修改日志",
                title = "定位修改 诊断日志",
                lines = { entries.toList() },
            )
            TextButton(onClick = { WebToolObserveLog.clear() }) {
                Text("清空")
            }
        }
        HorizontalDivider()
        if (entries.isEmpty()) {
            Text(
                "还没有记录——选择文件、在地图上选点、保存之后，过程会显示在这里",
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
