/*
 * 三个模块（定位修改 / 提词器 / 电话拦截）共用的诊断日志导出 —— 2026-09-05。
 *
 * 为什么这次提取了共享代码：`TeleprompterObserveLog` / `CallGuardObserveLog` 两个日志对象当初是
 * **故意各写一份、不提取公共类**的（见它们各自的注释：新功能不碰其他模块任何一行代码）。那个决定
 * 现在仍然有效——这里没有动那两个对象。但"导出成 txt、能转发微信、能存本地"是用户明确要求在三处
 * 都要有的**同一个**能力，三份各写一遍必然漂移（改了一处忘了另两处，这个项目已经在别处栽过）。
 * 所以只把**导出这件事**提出来，日志的采集/存储仍各归各的。
 *
 * 落盘位置分两种，对应用户说的两种用途：
 * - **转发微信** —— 写进 `sharedDir`（外部缓存目录，已被清单里那个 FileProvider 的
 *   `external-cache-path` 暴露），再用 `FileProvider.getUriForFile` + `ACTION_SEND` 拉起系统分享。
 *   走 FileProvider 是必须的：Android 7 之后直接 `file://` 递给别的应用会 FileUriExposedException。
 * - **保存本地** —— 写进公共下载目录（`MediaStore.Downloads`）。用 MediaStore 而不是
 *   `Environment.getExternalStoragePublicDirectory` + 写权限：minSdk 33，分区存储下前者不需要任何
 *   运行时权限，后者需要而且在新系统上根本写不进去。
 */
package li.songe.gkd.observelog

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import li.songe.gkd.META
import li.songe.gkd.app
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.sharedDir
import li.songe.gkd.util.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ObserveLogExport"

private val fileStampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
private val headerStampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

/** 导出文件的正文。头部那几行是给"日志发过来之后看的人"用的：光有时间戳行，分不出是哪个应用、
 *  哪个模块、什么时候导的——三个模块的行格式恰好一模一样。 */
private fun buildLogText(title: String, lines: List<String>): String = buildString {
    appendLine("${META.appName} · $title")
    appendLine("版本 ${META.versionName}（${META.versionCode}）")
    appendLine("导出时间 ${headerStampFormat.format(Date())}")
    appendLine("记录条数 ${lines.size}")
    appendLine("----------------------------------------")
    if (lines.isEmpty()) {
        appendLine("（没有记录）")
    } else {
        lines.forEach { appendLine(it) }
    }
}

private fun exportFileName(baseName: String): String =
    "$baseName-${fileStampFormat.format(Date())}.txt"

/** 写进 sharedDir 并拉起系统分享（微信、QQ 等都在这个列表里）。 */
private fun shareAsTxt(context: Context, baseName: String, title: String, lines: List<String>) {
    try {
        val file = sharedDir.resolve(exportFileName(baseName))
        file.writeText(buildLogText(title, lines))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            // 只放 EXTRA_STREAM，不放 EXTRA_TEXT：同时给这两个时，微信会优先当"分享一段文字"处理，
            // 发出去的是被截断的纯文本而不是文件。
            type = "text/plain"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        LogUtils.d("share log, file=${file.name}, lines=${lines.size}", tag = TAG)
        context.startActivity(Intent.createChooser(intent, "转发日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        LogUtils.d("share log failed", e, tag = TAG)
        toast("导出失败: ${e.message}")
    }
}

/** 写进系统下载目录的「<应用名>日志」子目录。 */
private fun saveToDownloads(context: Context, baseName: String, title: String, lines: List<String>) {
    val fileName = exportFileName(baseName)
    val relativeDir = "Download/${META.appName}日志"
    try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
        if (uri == null) {
            toast("保存失败：系统没有分配下载文件")
            return
        }
        context.contentResolver.openOutputStream(uri).use { out ->
            if (out == null) {
                context.contentResolver.delete(uri, null, null)
                toast("保存失败：打不开下载文件")
                return
            }
            out.write(buildLogText(title, lines).toByteArray(Charsets.UTF_8))
        }
        // IS_PENDING 清零之前，这个文件对文件管理器是不可见的——忘了这一步的表现是"提示保存成功但
        // 用户在下载里找不到"。
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        LogUtils.d("save log, file=$relativeDir/$fileName, lines=${lines.size}", tag = TAG)
        toast("已保存到 $relativeDir/$fileName")
    } catch (e: Exception) {
        LogUtils.d("save log failed", e, tag = TAG)
        toast("保存失败: ${e.message}")
    }
}

/**
 * 三个日志查看器顶栏共用的「导出」按钮：点开是两项菜单——转发（微信/QQ…）和保存到本地。
 *
 * [lines] 传 lambda 不传 List：日志是 `mutableStateListOf`，边看边可能有新行进来，取值要发生在
 * 点菜单的那一刻，不是这个组合函数上一次重组的时刻。
 */
@Composable
fun ObserveLogExportAction(
    baseName: String,
    title: String,
    lines: () -> List<String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // 按钮和菜单包在同一个 Box 里：DropdownMenu 是按**父布局节点**定位的，直接并排放在顶栏那个
    // Row 里会以整条 Row 为锚点弹出，位置跟按钮对不上。
    Box(modifier) {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("导出")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("转发（微信 / QQ…）") },
                onClick = {
                    expanded = false
                    shareAsTxt(context, baseName, title, lines())
                },
            )
            DropdownMenuItem(
                text = { Text("保存到本地（下载目录）") },
                onClick = {
                    expanded = false
                    saveToDownloads(context, baseName, title, lines())
                },
            )
        }
    }
}
