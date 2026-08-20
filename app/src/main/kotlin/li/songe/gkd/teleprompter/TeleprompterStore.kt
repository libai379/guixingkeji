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
).also {
    LogUtils.d(
        "load library, docs=${it.value.documents.size}, file=teleprompter_library.json",
        tag = TAG,
    )
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

suspend fun Activity.exportTeleprompterDocumentAsWord(doc: TeleprompterDocument) = withContext(Dispatchers.Default) {
    try {
        val bytes = buildDocxBytes(doc)
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
