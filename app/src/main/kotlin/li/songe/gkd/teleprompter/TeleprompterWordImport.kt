package li.songe.gkd.teleprompter

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.songe.gkd.app
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.sharedDir
import java.util.zip.ZipInputStream

private const val TAG = "Prompter"
private const val MAX_DOCX_BYTES = 30 * 1024 * 1024

fun isDocxUri(uri: Uri): Boolean {
    val type = app.contentResolver.getType(uri)
    if (type != null && type.contains("wordprocessingml")) return true
    val name = uri.lastPathSegment ?: return false
    return name.endsWith(".docx", ignoreCase = true)
}

/**
 * Step 3 minimal verification (not the real parser yet): read the docx, list its zip entries,
 * and dump word/document.xml + word/styles.xml in full to sharedDir (pulled off-device via adb
 * for inspection) so parsing logic gets written against real Word/WPS-generated XML instead of
 * an imagined structure - the first round already caught a real surprise: WPS emits numeric
 * pStyle ids ("2") instead of named ones ("Heading1"), which only styles.xml can resolve.
 */
suspend fun logDocxStructureForInspection(uri: Uri) = withContext(Dispatchers.IO) {
    try {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            LogUtils.d("docx inspect: could not open input stream, uri=$uri", tag = TAG)
            return@withContext
        }
        LogUtils.d("docx inspect: read ${bytes.size} bytes, uri=$uri", tag = TAG)

        val entryNames = mutableListOf<String>()
        val wanted = mapOf(
            "word/document.xml" to "docx-inspect-document.xml",
            "word/styles.xml" to "docx-inspect-styles.xml",
        )
        val written = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                val outName = wanted[entry.name]
                if (outName != null) {
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    val outFile = sharedDir.resolve(outName)
                    outFile.writeText(content)
                    written.add("$outName (${content.length} chars) <- ${entry.name}")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        LogUtils.d(
            "docx inspect: ${entryNames.size} zip entries:\n${entryNames.joinToString("\n")}",
            tag = TAG,
        )

        if (written.isEmpty()) {
            LogUtils.d(
                "docx inspect: neither word/document.xml nor word/styles.xml found in zip",
                tag = TAG,
            )
        } else {
            LogUtils.d(
                "docx inspect: wrote full XML to sharedDir (${sharedDir.absolutePath}):\n" +
                    written.joinToString("\n"),
                tag = TAG,
            )
        }
    } catch (e: Exception) {
        LogUtils.d("docx inspect failed, uri=$uri", e, tag = TAG)
    }
}

private fun queryDisplayName(uri: Uri): String? = try {
    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (e: Exception) {
    LogUtils.d("docx import: query display name failed, uri=$uri", e, tag = TAG)
    null
}

/**
 * Reads+unzips the docx, resolves styles.xml/theme1.xml, and hands document.xml off to
 * [parseDocxDocument]. Every failure path returns a Result with a specific reason instead of a
 * generic "import failed" - "常见文档能用，不支持的优雅跳过，任何情况不崩" means the one thing
 * this must never do is throw past this function.
 */
suspend fun importDocxFile(uri: Uri): Result<DocxImportSummary> = withContext(Dispatchers.Default) {
    try {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "导入文档"
        val title = displayName.removeSuffix(".docx").removeSuffix(".DOCX").ifBlank { "导入文档" }

        val bytes = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return@withContext Result.failure(DocxParseException("无法打开文件，请确认微信/文件管理器给了读取权限"))

        if (bytes.size > MAX_DOCX_BYTES) {
            return@withContext Result.failure(
                DocxParseException("文件太大（${bytes.size / 1024 / 1024}MB），超过 ${MAX_DOCX_BYTES / 1024 / 1024}MB 上限"),
            )
        }
        LogUtils.d("docx import: read ${bytes.size} bytes, title=$title, uri=$uri", tag = TAG)

        var documentXml: String? = null
        var stylesXml: String? = null
        var themeXml: String? = null
        // 2026-08-25 - 只有本 app 自己导出的 docx 才会带这个部件（见 TeleprompterDocxSettings.kt），
        // 纯 Word/WPS 原生文档没有，entry 名字压根不会出现，customSettingsXml 保持 null，走原有
        // "用本地设置"的路径，不受影响。
        var customSettingsXml: String? = null
        var entryCount = 0
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    when (entry.name) {
                        "word/document.xml" -> documentXml = zip.readBytes().toString(Charsets.UTF_8)
                        "word/styles.xml" -> stylesXml = zip.readBytes().toString(Charsets.UTF_8)
                        "word/theme/theme1.xml" -> themeXml = zip.readBytes().toString(Charsets.UTF_8)
                        DOCX_SETTINGS_CUSTOM_XML_PATH -> customSettingsXml = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(DocxParseException("解压失败，文件可能已损坏: ${e.message}"))
        }
        LogUtils.d("docx import: $entryCount zip entries, hasStyles=${stylesXml != null}, hasTheme=${themeXml != null}", tag = TAG)

        // Always dumped (not just on failure) - re-diagnosing a "wrong chapter split" style
        // report needs the exact XML the parser saw, and asking the user to re-share a file a
        // second time is friction we can just avoid by keeping this on every import.
        runCatching {
            val stamp = System.currentTimeMillis()
            documentXml?.let { sharedDir.resolve("docx-import-$stamp-document.xml").writeText(it) }
            stylesXml?.let { sharedDir.resolve("docx-import-$stamp-styles.xml").writeText(it) }
        }

        val xml = documentXml
            ?: return@withContext Result.failure(DocxParseException("压缩包里没有 word/document.xml，这不是有效的 .docx 文件"))

        val summary = parseDocxDocument(xml, stylesXml, themeXml, title, customSettingsXml)
        Result.success(summary)
    } catch (e: Exception) {
        LogUtils.d("docx import failed, uri=$uri", e, tag = TAG)
        Result.failure(DocxParseException(e.message ?: e::class.simpleName ?: "未知错误"))
    }
}

/** Shared by both entry points (library "导入" button + WeChat "用其他应用打开"): parse then
 * immediately materialize a new document via the VM, so callers just get back where to navigate. */
suspend fun importDocxAndCreateDocument(
    vm: TeleprompterVm,
    uri: Uri,
): Result<Pair<String, DocxImportSummary>> {
    val result = importDocxFile(uri)
    return result.map { summary ->
        val docId = vm.createDocumentFromImport(
            summary.title,
            summary.chapters,
            summary.fontFamily,
            summary.subtitleStyle,
            summary.displayOverrides,
            summary.editZoomScale,
        )
        docId to summary
    }
}

fun DocxImportSummary.describe(): String {
    val fontLabel = when (fontFamily) {
        TeleprompterFontFamily.SansSerif -> "无衬线"
        TeleprompterFontFamily.Serif -> "衬线"
    }
    return buildString {
        append("导入完成：${chapters.size} 个章节")
        headingLevelUsed?.let { append("，按 ${it + 1} 级标题分章") }
        if (skippedCount > 0) append("，跳过 $skippedCount 个不支持的元素")
        append("，字体已统一为 $fontLabel")
        dominantFontName?.let { append("（原字体：$it）") }
        // 2026-08-25 - 不打断操作的轻量提示，跟其余内容拼在同一条 toast 里，不单开一个对话框。
        // 只有重新导入本 app 自己导出的 docx 才会出现这句；纯 Word 来源、或本 app 更早版本导出
        // （还没有这个自定义部件）的文件，这几个字段全是默认值，这句不出现，行为等同现在。
        val hasCustomDisplaySettings = editZoomScale != null || listOf(
            displayOverrides.fontSizeSp, displayOverrides.overallScale, displayOverrides.lineHeightMultiplier,
            displayOverrides.contentWidthPortrait, displayOverrides.contentWidthLandscape,
        ).any { it != null } || displayOverrides.textAlignMode != null
        if (hasCustomDisplaySettings) append("，已应用文档自带的显示设置")
    }
}
