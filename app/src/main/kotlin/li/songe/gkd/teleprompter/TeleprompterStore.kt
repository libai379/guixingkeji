/*
 * 提词器的持久化入口。
 *
 * 全部文稿（正文 HTML、章节结构、每篇的样式设置）存在**一个** DataStore key 里：
 * `teleprompter_library`，落盘位置 `storeFolder`。注意 `FolderExt.kt` 里那个 `filesDir` 是私有的
 * 同名 val，**不等于 `Context.filesDir`**：没有 `.gkd` 标记文件时它取的是 `getExternalFilesDir`，
 * 实测真实路径是 `/storage/emulated/0/Android/data/<applicationId>/files/store/`（排查数据问题
 * 直接 adb pull 这个路径，`run-as` 进内部存储是空的）。这意味着：
 * - 清除应用数据 = 文稿全没，且只存在手机本地，没有云备份；
 * - `applicationId` 一变（私人版 vs 公开版）就是两个独立的存储，数据不互通。
 * 所以这里提供了导出单篇/导出整库的功能，要提醒用户定期用。
 *
 * 写入一律走 `updateTeleprompterLibrary { }`，它整体替换 StateFlow。注意这个替换成本不低——
 * 高频调用（比如绑在拖动手势上）会引发大范围重组，历史上造成过两次真实卡顿 bug。
 */
package li.songe.gkd.teleprompter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import li.songe.gkd.app
import li.songe.gkd.store.createAnyFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.json
import li.songe.gkd.util.sharedDir
import li.songe.gkd.util.toast
import li.songe.gkd.util.tryStartActivity

private const val TAG = "Prompter"

// debounced upstream (TeleprompterVm's 800ms LaunchedEffect) already gates write frequency,
// so the store's own debounce is left at 0 to avoid stacking two debounce windows.
val teleprompterLibraryFlow = createAnyFlow(
    key = "teleprompter_library",
    default = { TeleprompterLibrary() },
).also { flow ->
    LogUtils.d(
        "load library, docs=${flow.value.documents.size}, file=teleprompter_library.json",
        tag = TAG,
    )
    // 2026-08-26 - 五项显示设置从"全局 + 每篇可选覆盖"改成"每篇文档独立"的一次性数据迁移。
    // 放在这里（顶层 val 的初始化块内）而不是某个界面的 LaunchedEffect 里：这个 val 第一次被访问
    // 时就会跑完，任何读取方——ViewModel、编辑页、播放页、导出——都不可能拿到未迁移的值。
    //
    // 落盘必须显式调 persistNow()，不能指望"改了 flow.value 就会自动写"。createTextFlow 的写盘
    // 协程是 scope.launch 起的，这个初始化块跑的时候它**还没开始收集**；等它开始收集时，
    // MutableStateFlow 把当前值（已经是迁移后的）当第一个值发过去，正好被 drop(1) 丢掉，于是一个
    // 字都不会写。这不是推测：第一版就是这么写的，真机上日志打了 migrated、拉下来的文件却还是旧
    // 格式，schemaVersion 都没有。
    val beforeVersion = flow.value.schemaVersion
    val migrated = flow.value.migrateToPerDocumentDisplaySettings()
    if (migrated != flow.value) {
        flow.value = migrated
        flow.persistNow()
        LogUtils.d(
            "migrated library, docs=${migrated.documents.size}, " +
                "schemaVersion=$beforeVersion -> ${migrated.schemaVersion}, persisted=true",
            tag = TAG,
        )
    }
}

private val prettyJson by lazy { Json(from = json) { prettyPrint = true } }

fun updateTeleprompterLibrary(mutate: (TeleprompterLibrary) -> TeleprompterLibrary) {
    val prev = teleprompterLibraryFlow.value
    val next = try {
        mutate(prev)
    } catch (e: Exception) {
        LogUtils.d("update library failed", e, tag = TAG)
        return
    }
    val bytes = try {
        json.encodeToString(next).toByteArray(Charsets.UTF_8).size
    } catch (e: Exception) {
        LogUtils.d("encode library for log failed", e, tag = TAG)
        -1
    }
    LogUtils.d(
        "save library, docs=${next.documents.size}, bytes=$bytes, file=teleprompter_library.json",
        tag = TAG,
    )
    teleprompterLibraryFlow.value = next
}

private fun documentExportFileName(doc: TeleprompterDocument): String {
    val safeTitle = doc.title.ifBlank { "未命名文档" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return "提词器-$safeTitle-${System.currentTimeMillis()}.json"
}

/** Writes [text] into sharedDir (external cache, exposed by the existing FileProvider) and fires a share chooser. */
private fun Activity.shareJsonText(fileName: String, text: String) {
    try {
        val file = sharedDir.resolve(fileName)
        file.writeText(text)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension("json") ?: "application/json"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        LogUtils.d("export share, file=$fileName, bytes=${text.length}", tag = TAG)
        tryStartActivity(Intent.createChooser(intent, "导出提词稿"))
    } catch (e: Exception) {
        LogUtils.d("export share failed, file=$fileName", e, tag = TAG)
    }
}

fun Activity.exportTeleprompterDocument(doc: TeleprompterDocument) {
    shareJsonText(documentExportFileName(doc), prettyJson.encodeToString(doc))
}

private fun documentExportDocxFileName(doc: TeleprompterDocument): String {
    val safeTitle = doc.title.ifBlank { "未命名文档" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return "$safeTitle.docx"
}

// 2026-08-25 - effectiveSettings 由调用方传入（TeleprompterLibraryScreen 那边算 doc.effectiveSettings
// (library.settings)），这里不重新读一遍 - 这个函数本身没有拿到 library 的路径，且"用哪份合并后的
// 有效值"这件事只该有一处判断（TeleprompterModel.kt 的 effectiveSettings()），不在这再猜一遍。
suspend fun Activity.exportTeleprompterDocumentAsWord(
    doc: TeleprompterDocument,
    effectiveSettings: TeleprompterSettings,
) = withContext(Dispatchers.Default) {
    try {
        val bytes = buildDocxBytes(doc, effectiveSettings)
        val fileName = documentExportDocxFileName(doc)
        val file = sharedDir.resolve(fileName)
        file.writeBytes(bytes)
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            LogUtils.d("word export share, file=$fileName, bytes=${bytes.size}", tag = TAG)
            tryStartActivity(Intent.createChooser(intent, "导出为 Word"))
        }
    } catch (e: Exception) {
        LogUtils.d("word export failed, docId=${doc.id}", e, tag = TAG)
        withContext(Dispatchers.Main) { toast("导出 Word 失败: ${e.message}") }
    }
}

fun Activity.exportTeleprompterLibrary() {
    val library = teleprompterLibraryFlow.value
    shareJsonText(
        "提词器备份-${System.currentTimeMillis()}.json",
        prettyJson.encodeToString(library),
    )
}

/** Auto-detects a single-document export vs a full-library backup, and upserts by id. */
suspend fun importTeleprompterFile(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
    try {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
            ?: return@withContext Result.failure(IllegalStateException("无法读取文件"))
        // A bare document JSON would also "successfully" decode as an all-defaults
        // TeleprompterLibrary (ignoreUnknownKeys), silently dropping it to zero docs -
        // so branch on the raw "documents" key instead of try/catch-ing the decode.
        val root = json.parseToJsonElement(text).jsonObject
        val importedDocs = if (root.containsKey("documents")) {
            json.decodeFromString<TeleprompterLibrary>(text).documents
        } else {
            listOf(json.decodeFromString<TeleprompterDocument>(text))
        }
        updateTeleprompterLibrary { library ->
            val byId = library.documents.associateBy { it.id }.toMutableMap()
            importedDocs.forEach { byId[it.id] = it }
            library.copy(documents = byId.values.toList())
        }
        LogUtils.d("import ok, uri=$uri, docs=${importedDocs.size}", tag = TAG)
        Result.success(importedDocs.size)
    } catch (e: Exception) {
        LogUtils.d("import failed, uri=$uri", e, tag = TAG)
        Result.failure(e)
    }
}
