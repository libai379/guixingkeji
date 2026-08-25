/*
 * 液态玻璃预设的"文件级"导出/导入 —— 2026-08-25，面向用户备份/分享，走真实文件 + 系统分享/文件
 * 关联。**跟 GlassTuningActivity 里那两个"导出 JSON"/"导出 Kotlin"剪贴板按钮是两条独立的路**，
 * 不共用序列化逻辑：那两个按钮导出的是"当前单个 target 的参数"，是给开发者调参时贴回源码用的；
 * 这里导出的是"一个预设跨全部 target 的完整快照"，格式、用途、读者都不一样，硬凑一份序列化逻辑
 * 会让两边互相牵制（改一处怕影响另一处），拆开更省事。
 *
 * 文件后缀 `.guiyue.json`（不用裸 `.json`——那样任何 app 导出的任意 json 都会被拉进"打开方式"
 * 选择列表，列表会很杂；也不用带 `gkd` 的名字——那是上游项目名，这个 app 已经改名癸月/癸月科技，
 * 公开版用户看到 gkd 字样会困惑）。
 */
package li.songe.gkd.glasstuning

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import li.songe.gkd.app
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.json
import li.songe.gkd.util.sharedDir
import li.songe.gkd.util.tryStartActivity

private const val TAG = "GlassPreset"
const val GLASS_PRESET_FILE_SUFFIX = ".guiyue.json"

/**
 * 磁盘上的文件格式，跟 [GlassPreset]（应用内存储结构）故意分开成两个类——存储结构以后要加字段
 * 很自由，不用担心牵动老导出文件的解析；文件格式只在这里改，改的时候能一眼看到影响范围。
 *
 * [formatVersion] 是这份格式自己的版本号，跟应用的 versionCode 无关。当前只有 1。以后格式真的
 * 需要不兼容变更时，新版本号+旧版本号在这里都能显式处理，不是解析失败了才发现"原来是版本对不上"。
 */
@Serializable
private data class GlassPresetFile(
    val formatVersion: Int,
    val name: String,
    val paramsByTarget: Map<String, GlassParams>,
)

private const val CURRENT_FORMAT_VERSION = 1

/** JSON 语法都不对，或者结构完全不是这个格式（比如拖了张图片进来、拖了别的 app 导出的 json）。 */
class GlassPresetMalformedException(message: String) : Exception(message)

/** 语法对、结构对，但 formatVersion 不是这个应用认识的——比如未来某个更新版本导出的文件，拿旧
 *  版本应用打开。单独一个异常类型是为了不用猜着往下解析：版本不认识就直接说清楚，不强行读取
 *  一份自己看不懂的格式后硬凑出错乱数据。 */
class GlassPresetUnknownVersionException(val fileVersion: Int) :
    Exception("这份预设是版本 $fileVersion 导出的，当前应用只认识版本 $CURRENT_FORMAT_VERSION")

/** 解析成功后还没写入存储的中间态：拿到里面存的名字（给命名对话框当默认值用）和参数表。 */
data class ParsedGlassPreset(
    val suggestedName: String,
    val paramsByTarget: Map<String, GlassParams>,
)

private val presetFileJson by lazy { Json(from = json) { prettyPrint = true } }

fun GlassPreset.toPortableJson(): String =
    presetFileJson.encodeToString(
        GlassPresetFile(formatVersion = CURRENT_FORMAT_VERSION, name = name, paramsByTarget = paramsByTarget),
    )

/** 纯解析，不碰存储——commitImportedGlassPreset 才是真正写进 glassPresetStoreFlow 那一步，两步
 *  分开是为了让调用方能在两步之间插入"问用户要不要改个名字"的对话框。 */
fun parseGlassPresetPortableJson(text: String): Result<ParsedGlassPreset> {
    val file = try {
        presetFileJson.decodeFromString<GlassPresetFile>(text)
    } catch (e: Exception) {
        LogUtils.d("preset parse failed", e, tag = TAG)
        return Result.failure(GlassPresetMalformedException(e.message ?: "文件格式不对，不是有效的预设文件"))
    }
    if (file.formatVersion != CURRENT_FORMAT_VERSION) {
        return Result.failure(GlassPresetUnknownVersionException(file.formatVersion))
    }
    if (file.paramsByTarget.isEmpty()) {
        return Result.failure(GlassPresetMalformedException("这份预设不包含任何参数"))
    }
    return Result.success(ParsedGlassPreset(suggestedName = file.name, paramsByTarget = file.paramsByTarget))
}

/** 真正写入存储 —— 直接追加一条新预设，不经过任何 target 白名单过滤：文件里存的
 *  paramsByTarget 覆盖哪些 target，导入后就是哪些 target，跟 saveGlassPreset()（从当前调参面板
 *  快照）语义一致。它会出现在哪个入口的预设列表里，由 allPresetsFor() 现成的"key 集合精确匹配"
 *  规则自动决定，这里不用关心。 */
fun commitImportedGlassPreset(parsed: ParsedGlassPreset, name: String) {
    val preset = GlassPreset(name = name, paramsByTarget = parsed.paramsByTarget)
    glassPresetStoreFlow.value = glassPresetStoreFlow.value.copy(
        presets = glassPresetStoreFlow.value.presets + preset,
    )
}

suspend fun importGlassPresetFromUri(uri: Uri): Result<ParsedGlassPreset> = withContext(Dispatchers.IO) {
    try {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
            ?: return@withContext Result.failure(IllegalStateException("无法读取文件"))
        parseGlassPresetPortableJson(text)
    } catch (e: Exception) {
        LogUtils.d("preset import read failed, uri=$uri", e, tag = TAG)
        Result.failure(e)
    }
}

private fun presetExportFileName(name: String): String {
    val safe = name.ifBlank { "预设" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return "$safe$GLASS_PRESET_FILE_SUFFIX"
}

/** 写文件到 sharedDir（已注册的 FileProvider 覆盖范围）+ 拉起系统分享面板 —— 跟
 *  TeleprompterStore.kt 的 shareJsonText 是同一个模式，这里单独写一份而不是复用它，是因为文件名/
 *  MIME 处理各有各的产出格式，混一起判断起来更绕。 */
fun Activity.exportGlassPresetToFile(preset: GlassPreset) {
    try {
        val fileName = presetExportFileName(preset.name)
        val file = sharedDir.resolve(fileName)
        file.writeText(preset.toPortableJson())
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension("json") ?: "application/json"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        LogUtils.d("preset export share, file=$fileName", tag = TAG)
        tryStartActivity(Intent.createChooser(intent, "导出预设"))
    } catch (e: Exception) {
        LogUtils.d("preset export failed, preset=${preset.name}", e, tag = TAG)
    }
}
