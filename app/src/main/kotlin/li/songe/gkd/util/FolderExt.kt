package li.songe.gkd.util

import android.text.format.DateUtils
import li.songe.gkd.app
import java.io.File

fun File.autoMk(): File {
    if (!exists()) {
        mkdirs()
    }
    return this
}

private val filesDir: File by lazy {
    val markFile = app.filesDir.resolve(".gkd")
    if (markFile.isFile) {
        app.filesDir
    } else {
        // fix #1333
        app.getExternalFilesDir(null) ?: app.filesDir.also {
            markFile.createNewFile()
        }
    }
}

val dbFolder: File
    get() = filesDir.resolve("db").autoMk()
val shFolder: File
    get() = filesDir.resolve("sh").autoMk()
val storeFolder: File
    get() = filesDir.resolve("store").autoMk()
val subsFolder: File
    get() = filesDir.resolve("subscription").autoMk()
val snapshotFolder: File
    get() = filesDir.resolve("snapshot").autoMk()
val logFolder: File
    get() = filesDir.resolve("log").autoMk()
val crashFolder: File
    get() = filesDir.resolve("crash").autoMk()
val crashTempFolder: File
    get() = filesDir.resolve("crash/temp").autoMk()

val privateStoreFolder: File
    get() = app.filesDir.resolve("private-store").autoMk()

// A user-picked image (提词器文档库背景) copied in here rather than keeping the picked content://
// URI around - Photo Picker's read grant isn't guaranteed to survive past the current process
// without an explicit takePersistableUriPermission call, and even that isn't honored by every
// provider. Owning a real copy of the bytes is the only reliable way to have this still be
// readable on a later app launch, not a documented project pitfall, just general Android URI
// lifetime behavior.
val teleprompterBgFolder: File
    get() = filesDir.resolve("teleprompter_bg").autoMk()

private val cacheDir by lazy { app.externalCacheDir ?: app.cacheDir }
val coilCacheDir: File
    get() = cacheDir.resolve("coil").autoMk()
val sharedDir: File
    get() = cacheDir.resolve("shared").autoMk()
private val tempDir: File
    get() = cacheDir.resolve("temp").autoMk()

fun createGkdTempDir(): File {
    return tempDir
        .resolve(System.currentTimeMillis().toString())
        .apply { mkdirs() }
}

private fun removeExpired(dir: File) {
    dir.listFiles()?.forEach { f ->
        if (System.currentTimeMillis() - f.lastModified() > DateUtils.HOUR_IN_MILLIS) {
            if (f.isDirectory) {
                f.deleteRecursively()
            } else if (f.isFile) {
                f.delete()
            }
        }
    }
}

fun clearCache() {
    removeExpired(sharedDir)
    removeExpired(tempDir)
}

// 公开版：这里原本还有 buildLogFile()（把数据库/订阅/日志/崩溃记录打包成 zip 供反馈用），依赖
// GKD 内核的 data/permission/shizuku 三个包，内核已整个删除，该功能一并去掉。上面这些目录定义
// 本身是自包含的，提词器等保留功能仍在用（sharedDir / storeFolder / teleprompterBgFolder）。
