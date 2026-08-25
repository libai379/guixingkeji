/*
 * 接住"打开方式"选中本 app 的 .guiyue.json 文件 —— 结构照抄 TeleprompterActivity 处理外部 docx
 * 打开的那一套（extractDocxUri + onCreate/onNewIntent 双路径喂同一个 pending uri + LaunchedEffect
 * 解析），因为场景完全一样：一个"接收外部文件、解析、问名字、写入"的轻量入口，不需要独立发明。
 *
 * 只处理 ACTION_VIEW（系统"打开方式"）。app 内部那条导入路径（预设区的"导入"按钮，用户主动选
 * 文件）走的是 GlassTuningActivity 里现成的 OpenDocument 选择器，不经过这个 Activity——两条路径
 * 殊途同归都调 [importGlassPresetFromUri]/[commitImportedGlassPreset]，解析和落库逻辑只有一份。
 */
package li.songe.gkd.glasstuning

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import li.songe.gkd.ui.style.AppTheme
import li.songe.gkd.util.LogUtils

private const val TAG = "GlassPreset"

class GlassPresetImportActivity : ComponentActivity() {
    // 跟 TeleprompterActivity 同样的理由：持有在 Activity 上而不是 Composable 里，这样
    // onNewIntent（这个 Activity 已经在前台时，系统再次用同一个文件打开它）能把新 uri 塞进去，
    // 不需要重建整个界面状态。
    private val pendingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUri.value = intent.data
        setContent {
            AppTheme {
                var parsed by remember { mutableStateOf<ParsedGlassPreset?>(null) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                val uri by pendingUri

                LaunchedEffect(uri) {
                    val u = uri ?: return@LaunchedEffect
                    LogUtils.d("import from file-association, uri=$u", tag = TAG)
                    importGlassPresetFromUri(u).fold(
                        onSuccess = { parsed = it },
                        onFailure = { e -> errorMessage = e.message ?: "导入失败" },
                    )
                }

                Box(Modifier.fillMaxSize())

                parsed?.let { p ->
                    PresetNameDialog(
                        title = "导入预设",
                        initialName = p.suggestedName,
                        confirmLabel = "导入",
                        onDismiss = { finish() },
                        onConfirm = { name ->
                            commitImportedGlassPreset(p, name)
                            parsed = null
                            finish()
                        },
                    )
                }
                errorMessage?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("导入失败") },
                        text = { Text(msg) },
                        confirmButton = {
                            TextButton(onClick = { finish() }) { Text("知道了") }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingUri.value = intent.data
    }
}
