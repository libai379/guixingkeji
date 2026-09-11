package com.guixing.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.guixing.appScope
import com.guixing.util.json
import com.guixing.util.privateStoreFolder
import com.guixing.util.storeFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption


private fun readStoreText(
    file: File
): String? = file.run {
    if (exists()) {
        readText()
    } else {
        null
    }
}

private fun writeStoreText(file: File, text: String) {
    val tempFile = File("${file.absolutePath}.tmp")
    tempFile.outputStream().use {
        it.write(text.toByteArray(Charsets.UTF_8))
        it.fd.sync()
    }
    Files.move(
        tempFile.toPath(),
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
    )
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class MutableStoreStateFlow<T>(
    val filename: String,
    val decode: (String?) -> T,
    val encode: (T) -> String,
    private val stateFlow: MutableStateFlow<T>,
    private val file: File,
) : MutableStateFlow<T> by stateFlow {
    fun encodeSelf(): String = encode(value)
    fun updateByDecode(text: String?) {
        value = decode(text)
    }

    /**
     * 立刻同步写盘，不经过下面那条 drop(1) 的异步收集管线。
     *
     * 2026-08-27 - 为数据迁移加的。迁移必须在**任何人读到这个 flow 之前**跑完，也就是在顶层 val
     * 的初始化块里；但那时 createTextFlow 里 `scope.launch` 起的写盘协程**还没开始收集**。等它真
     * 正开始收集时，MutableStateFlow 会把"当前值"（已经是迁移后的）作为第一个值发给它，正好被
     * `drop(1)` 丢掉——于是迁移结果永远不落盘，每次启动都在内存里重迁一遍。真机上就是这么表现的：
     * 日志打了 migrated，拉下来的文件却还是旧格式。
     *
     * 同步写在调用线程上完成（迁移场景下是主线程）。可以接受：只在需要迁移的那一次启动发生一次，
     * 而且同一个文件的读取（readStoreText）本来就是同线程同步做的，写这一次并不比它更重。
     */
    fun persistNow() {
        writeStoreText(file, encodeSelf())
    }
}

fun <T> createTextFlow(
    key: String,
    decode: (String?) -> T,
    encode: (T) -> String,
    private: Boolean = false,
    scope: CoroutineScope = appScope,
    debounceMillis: Long = 0,
): MutableStoreStateFlow<T> {
    val filename = if (key.contains('.')) key else "$key.txt"
    val file = (if (private) privateStoreFolder else storeFolder).resolve(filename)
    val initText = readStoreText(file)
    val initValue = decode(initText)
    val stateFlow = MutableStateFlow(initValue)
    scope.launch {
        stateFlow.drop(1).conflate().debounce(debounceMillis).collect {
            withContext(Dispatchers.IO) {
                writeStoreText(file, encode(it))
            }
        }
    }
    return MutableStoreStateFlow(
        filename = filename,
        decode = decode,
        encode = encode,
        stateFlow = stateFlow,
        file = file,
    )
}

inline fun <reified T> createAnyFlow(
    key: String,
    crossinline default: () -> T,
    crossinline initialize: (T) -> T = { it },
    private: Boolean = false,
    scope: CoroutineScope = appScope,
    debounceMillis: Long = 0,
): MutableStoreStateFlow<T> {
    return createTextFlow(
        key = "$key.json",
        decode = {
            val initValue = it?.let {
                runCatching { json.decodeFromString<T>(it) }.getOrNull()
            }
            initialize(initValue ?: default())
        },
        encode = {
            json.encodeToString(it)
        },
        private = private,
        scope = scope,
        debounceMillis = debounceMillis,
    )
}
