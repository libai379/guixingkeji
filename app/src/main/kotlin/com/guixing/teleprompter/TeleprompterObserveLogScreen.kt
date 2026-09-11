package com.guixing.teleprompter

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
import androidx.compose.material.icons.filled.Close
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

/**
 * In-app viewer for TeleprompterObserveLog - see that object's doc comment for why this exists
 * (adb unreachable while debugging the demote-to-body content-loss bug).
 */
@Composable
fun TeleprompterObserveLogScreen(onClose: () -> Unit) {
    val entries = TeleprompterObserveLog.entries
    val clipboard = LocalClipboardManager.current
    val horizontalScrollState = rememberScrollState()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
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
                baseName = "提词器日志",
                title = "提词器 诊断日志",
                lines = { entries.toList() },
            )
            TextButton(onClick = { TeleprompterObserveLog.clear() }) {
                Text("清空")
            }
        }
        HorizontalDivider()
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
